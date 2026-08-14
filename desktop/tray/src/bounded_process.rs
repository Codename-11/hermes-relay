//! Bounded execution for short-lived Windows helper commands.
//!
//! Each invocation owns a Job Object. Closing it terminates any processes that
//! the command spawned, so a timed-out CLI cannot leave an orphaned process
//! tree behind. Output is drained concurrently while retaining only a bounded
//! prefix for diagnostics and JSON parsing.

use std::{
    fmt,
    io::{self, Read},
    mem::size_of,
    os::windows::{io::AsRawHandle, process::CommandExt},
    process::{Command, ExitStatus, Stdio},
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Mutex,
    },
    thread::{self, JoinHandle},
    time::{Duration, Instant},
};

use windows::{
    core::PCWSTR,
    Win32::{
        Foundation::{CloseHandle, HANDLE},
        System::{
            JobObjects::{
                AssignProcessToJobObject, CreateJobObjectW, JobObjectExtendedLimitInformation,
                SetInformationJobObject, JOBOBJECT_EXTENDED_LIMIT_INFORMATION,
                JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE,
            },
            Threading::CREATE_NO_WINDOW,
            IO::CancelSynchronousIo,
        },
    },
};

const WAIT_POLL_INTERVAL: Duration = Duration::from_millis(10);

#[derive(Clone, Copy, Debug)]
pub(crate) struct RunOptions {
    pub(crate) timeout: Duration,
    /// Maximum number of bytes retained from each output stream.
    pub(crate) max_capture_bytes: usize,
    pub(crate) process_tree: ProcessTreePolicy,
}

impl RunOptions {
    pub(crate) const fn new(timeout: Duration, max_capture_bytes: usize) -> Self {
        Self {
            timeout,
            max_capture_bytes,
            process_tree: ProcessTreePolicy::KillDescendants,
        }
    }

    pub(crate) const fn direct_child_only(mut self) -> Self {
        self.process_tree = ProcessTreePolicy::DirectChildOnly;
        self
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum ProcessTreePolicy {
    /// Contain the invocation in a Job Object and end every descendant when the
    /// direct child exits or times out. This is the safe default for probes.
    KillDescendants,
    /// Allow a successful launcher to leave a deliberately detached daemon.
    /// On timeout, only the direct child can be terminated.
    DirectChildOnly,
}

#[derive(Clone, Debug, Default)]
pub(crate) struct CapturedOutput {
    pub(crate) bytes: Vec<u8>,
    pub(crate) total_bytes: u64,
    pub(crate) truncated: bool,
}

#[derive(Debug)]
pub(crate) struct ProcessOutcome {
    pub(crate) status: ExitStatus,
    pub(crate) stdout: CapturedOutput,
    pub(crate) stderr: CapturedOutput,
    pub(crate) timed_out: bool,
    pub(crate) duration: Duration,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum ProcessStage {
    CreateJob,
    ConfigureJob,
    Spawn,
    AssignJob,
    Wait,
    ReadStdout,
    ReadStderr,
}

#[derive(Debug)]
pub(crate) struct ProcessError {
    pub(crate) stage: ProcessStage,
    pub(crate) duration: Duration,
    source: Box<dyn std::error::Error + Send + Sync>,
}

impl ProcessError {
    fn new(
        stage: ProcessStage,
        duration: Duration,
        source: impl std::error::Error + Send + Sync + 'static,
    ) -> Self {
        Self {
            stage,
            duration,
            source: Box::new(source),
        }
    }
}

impl fmt::Display for ProcessError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            formatter,
            "process {:?} failed after {:?}: {}",
            self.stage, self.duration, self.source
        )
    }
}

impl std::error::Error for ProcessError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        Some(self.source.as_ref())
    }
}

struct KillOnCloseJob(Option<HANDLE>);

impl KillOnCloseJob {
    fn create(started: Instant) -> Result<Self, ProcessError> {
        let handle = unsafe { CreateJobObjectW(None, PCWSTR::null()) }.map_err(|error| {
            ProcessError::new(ProcessStage::CreateJob, started.elapsed(), error)
        })?;

        let mut limits = JOBOBJECT_EXTENDED_LIMIT_INFORMATION::default();
        limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
        let configured = unsafe {
            SetInformationJobObject(
                handle,
                JobObjectExtendedLimitInformation,
                (&limits as *const JOBOBJECT_EXTENDED_LIMIT_INFORMATION).cast(),
                size_of::<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>() as u32,
            )
        };
        if let Err(error) = configured {
            unsafe {
                let _ = CloseHandle(handle);
            }
            return Err(ProcessError::new(
                ProcessStage::ConfigureJob,
                started.elapsed(),
                error,
            ));
        }

        Ok(Self(Some(handle)))
    }

    fn assign(&self, process: HANDLE, started: Instant) -> Result<(), ProcessError> {
        unsafe { AssignProcessToJobObject(self.0.expect("open job handle"), process) }
            .map_err(|error| ProcessError::new(ProcessStage::AssignJob, started.elapsed(), error))
    }

    /// Closing a KILL_ON_JOB_CLOSE job is the process-tree termination signal.
    fn close(&mut self) {
        if let Some(handle) = self.0.take() {
            unsafe {
                let _ = CloseHandle(handle);
            }
        }
    }
}

impl Drop for KillOnCloseJob {
    fn drop(&mut self) {
        self.close();
    }
}

/// Runs a command with bounded lifetime, process-tree ownership, and output.
///
/// The command's stdin is disconnected and stdout/stderr are replaced with
/// pipes. The retained bytes are a prefix; readers continue draining after the
/// limit so verbose children cannot deadlock on a full pipe.
pub(crate) fn run(
    command: &mut Command,
    options: RunOptions,
) -> Result<ProcessOutcome, ProcessError> {
    let started = Instant::now();
    let mut job = match options.process_tree {
        ProcessTreePolicy::KillDescendants => Some(KillOnCloseJob::create(started)?),
        ProcessTreePolicy::DirectChildOnly => None,
    };

    command
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .creation_flags(CREATE_NO_WINDOW.0);

    let mut child = command
        .spawn()
        .map_err(|error| ProcessError::new(ProcessStage::Spawn, started.elapsed(), error))?;

    let process_handle = HANDLE(child.as_raw_handle());
    if let Some(job) = job.as_ref() {
        if let Err(error) = job.assign(process_handle, started) {
            let _ = child.kill();
            let _ = child.wait();
            return Err(error);
        }
    }

    // Start both readers only after containment succeeds, but before waiting.
    let stdout = child.stdout.take().expect("stdout was configured as piped");
    let stderr = child.stderr.take().expect("stderr was configured as piped");
    let stdout_reader = OutputReader::spawn(stdout, options.max_capture_bytes);
    let stderr_reader = OutputReader::spawn(stderr, options.max_capture_bytes);

    let deadline = started.checked_add(options.timeout);
    let mut timed_out = false;
    let status = loop {
        match child.try_wait() {
            Ok(Some(status)) => break status,
            Ok(None) => {}
            Err(error) => {
                if let Some(job) = job.as_mut() {
                    job.close();
                } else {
                    let _ = child.kill();
                }
                let _ = child.wait();
                return Err(ProcessError::new(
                    ProcessStage::Wait,
                    started.elapsed(),
                    error,
                ));
            }
        }

        if match deadline {
            Some(deadline) => Instant::now() >= deadline,
            None => true,
        } {
            timed_out = true;
            if let Some(job) = job.as_mut() {
                job.close();
            } else {
                let _ = child.kill();
            }
            break child.wait().map_err(|error| {
                ProcessError::new(ProcessStage::Wait, started.elapsed(), error)
            })?;
        }

        let remaining = deadline
            .and_then(|deadline| deadline.checked_duration_since(Instant::now()))
            .unwrap_or(Duration::ZERO);
        thread::sleep(WAIT_POLL_INTERVAL.min(remaining));
    };

    // End any descendants that outlived the direct child, then collect EOF from
    // both pipes. This also prevents successful helper commands from leaking.
    if let Some(job) = job.as_mut() {
        job.close();
    }
    let allow_detached_descendants = options.process_tree == ProcessTreePolicy::DirectChildOnly;
    let stdout = stdout_reader.finish(
        ProcessStage::ReadStdout,
        started,
        allow_detached_descendants,
    )?;
    let stderr = stderr_reader.finish(
        ProcessStage::ReadStderr,
        started,
        allow_detached_descendants,
    )?;

    Ok(ProcessOutcome {
        status,
        stdout,
        stderr,
        timed_out,
        duration: started.elapsed(),
    })
}

struct OutputReader {
    thread: Option<JoinHandle<io::Result<()>>>,
    output: Arc<Mutex<CapturedOutput>>,
    stop: Arc<AtomicBool>,
}

impl OutputReader {
    fn spawn<R>(mut reader: R, max_capture_bytes: usize) -> Self
    where
        R: Read + Send + 'static,
    {
        let output = Arc::new(Mutex::new(CapturedOutput {
            bytes: Vec::with_capacity(max_capture_bytes.min(8 * 1024)),
            ..CapturedOutput::default()
        }));
        let stop = Arc::new(AtomicBool::new(false));
        let worker_output = Arc::clone(&output);
        let worker_stop = Arc::clone(&stop);
        let thread = thread::spawn(move || {
            let mut buffer = [0_u8; 8 * 1024];
            loop {
                if worker_stop.load(Ordering::Acquire) {
                    return Ok(());
                }
                let read = match reader.read(&mut buffer) {
                    Ok(read) => read,
                    Err(_) if worker_stop.load(Ordering::Acquire) => return Ok(()),
                    Err(error) => return Err(error),
                };
                if read == 0 {
                    return Ok(());
                }
                let mut output = worker_output
                    .lock()
                    .map_err(|_| io::Error::other("output capture lock was poisoned"))?;
                output.total_bytes = output.total_bytes.saturating_add(read as u64);
                let remaining = max_capture_bytes.saturating_sub(output.bytes.len());
                output
                    .bytes
                    .extend_from_slice(&buffer[..read.min(remaining)]);
                output.truncated = output.total_bytes > output.bytes.len() as u64;
            }
        });
        Self {
            thread: Some(thread),
            output,
            stop,
        }
    }

    fn finish(
        mut self,
        stage: ProcessStage,
        started: Instant,
        allow_detached_descendants: bool,
    ) -> Result<CapturedOutput, ProcessError> {
        let thread = self.thread.take().expect("reader thread is present");

        if allow_detached_descendants && !thread.is_finished() {
            // Give the direct child a moment to flush. If a deliberately
            // detached descendant inherited the pipe, cancel this thread's
            // pending read so the bounded runner does not wait for that daemon.
            let grace_deadline = Instant::now() + Duration::from_millis(25);
            while !thread.is_finished() && Instant::now() < grace_deadline {
                thread::sleep(Duration::from_millis(1));
            }
            self.stop.store(true, Ordering::Release);
            let cancel_deadline = Instant::now() + Duration::from_millis(100);
            while !thread.is_finished() && Instant::now() < cancel_deadline {
                unsafe {
                    let _ = CancelSynchronousIo(HANDLE(thread.as_raw_handle()));
                }
                thread::sleep(Duration::from_millis(1));
            }
        }

        if !thread.is_finished() && allow_detached_descendants {
            // Cancellation is best-effort at the Win32 boundary. Return the
            // bounded snapshot instead of allowing an inherited pipe to turn a
            // timeout into an unbounded wait; the stop flag retires the reader
            // after its pending operation returns.
            return self.snapshot(stage, started);
        }

        match thread.join() {
            Ok(Ok(())) => self.snapshot(stage, started),
            Ok(Err(error)) => Err(ProcessError::new(stage, started.elapsed(), error)),
            Err(_) => Err(ProcessError::new(
                stage,
                started.elapsed(),
                io::Error::other("output reader thread panicked"),
            )),
        }
    }

    fn snapshot(
        &self,
        stage: ProcessStage,
        started: Instant,
    ) -> Result<CapturedOutput, ProcessError> {
        self.output
            .lock()
            .map(|output| output.clone())
            .map_err(|_| {
                ProcessError::new(
                    stage,
                    started.elapsed(),
                    io::Error::other("output capture lock was poisoned"),
                )
            })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    fn cmd(script: &str) -> Command {
        let mut command = Command::new("cmd.exe");
        command.args(["/D", "/S", "/C", script]);
        command
    }

    #[test]
    fn captures_stdout_and_stderr() {
        let outcome = run(
            &mut cmd("echo stdout-text & echo stderr-text 1>&2"),
            RunOptions::new(Duration::from_secs(2), 1024),
        )
        .expect("command should run");

        assert!(outcome.status.success());
        assert!(!outcome.timed_out);
        assert!(String::from_utf8_lossy(&outcome.stdout.bytes).contains("stdout-text"));
        assert!(String::from_utf8_lossy(&outcome.stderr.bytes).contains("stderr-text"));
    }

    #[test]
    fn drains_output_after_capture_limit() {
        let outcome = run(
            &mut cmd("for /L %i in (1,1,2000) do @echo 12345678901234567890"),
            RunOptions::new(Duration::from_secs(5), 64),
        )
        .expect("verbose command should not deadlock");

        assert!(outcome.status.success());
        assert_eq!(outcome.stdout.bytes.len(), 64);
        assert!(outcome.stdout.truncated);
        assert!(outcome.stdout.total_bytes > outcome.stdout.bytes.len() as u64);
    }

    #[test]
    fn times_out_and_reaps_child() {
        let outcome = run(
            &mut cmd("ping 127.0.0.1 -n 30 >nul"),
            RunOptions::new(Duration::from_millis(100), 1024),
        )
        .expect("timed out command should still return an outcome");

        assert!(outcome.timed_out);
        assert!(outcome.duration < Duration::from_secs(5));
        assert!(outcome.status.code().is_some());
    }

    #[test]
    fn direct_child_mode_times_out_and_reaps_child() {
        let outcome = run(
            &mut cmd("ping 127.0.0.1 -n 30 >nul"),
            RunOptions::new(Duration::from_millis(100), 1024).direct_child_only(),
        )
        .expect("timed out direct child should still return an outcome");

        assert!(outcome.timed_out);
        assert!(outcome.duration < Duration::from_secs(5));
        assert!(outcome.status.code().is_some());
    }

    #[test]
    fn timeout_reaps_a_spawned_descendant() {
        let pid_path = std::env::temp_dir().join(format!(
            "hermes-bounded-process-descendant-{}-{}.txt",
            std::process::id(),
            Instant::now().elapsed().as_nanos()
        ));
        let escaped_path = pid_path.display().to_string().replace('\'', "''");
        let script = format!(
            "$child = Start-Process ping.exe -ArgumentList '127.0.0.1 -n 30' -WindowStyle Hidden -PassThru; Set-Content -LiteralPath '{escaped_path}' -Value $child.Id; Wait-Process -Id $child.Id"
        );
        let mut command = Command::new("powershell.exe");
        command.args(["-NoProfile", "-Command", &script]);
        let outcome = run(&mut command, RunOptions::new(Duration::from_secs(3), 1024))
            .expect("timed out process tree should return an outcome");
        assert!(outcome.timed_out);

        let pid = fs::read_to_string(&pid_path)
            .expect("descendant pid should be recorded")
            .trim()
            .parse::<u32>()
            .expect("descendant pid should be numeric");
        let probe = Command::new("powershell.exe")
            .args([
                "-NoProfile",
                "-Command",
                &format!("if (Get-Process -Id {pid} -ErrorAction SilentlyContinue) {{ exit 1 }}"),
            ])
            .status()
            .expect("descendant liveness probe should run");
        let _ = fs::remove_file(pid_path);
        assert!(probe.success(), "descendant process {pid} survived timeout");
    }
}
