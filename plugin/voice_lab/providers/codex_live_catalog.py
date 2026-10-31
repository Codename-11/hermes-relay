"""Model option for Relay's native Codex-backed GPT Live provider."""
from .base import ProviderInfo, ProviderUnavailable, VoiceProvider

class CodexLiveCatalog(VoiceProvider):
    info = ProviderInfo(
        id='codex_live', name='GPT-Live-1.1 (ChatGPT/Codex subscription)',
        description='Codex subscription WebRTC voice; Hermes owns tools and Android permissions. No OpenAI API key.',
        models=('gpt-live-1.1',),voices=('sol','cove','alloy','echo','sage','shimmer'),
        sample_rates=(24000,),supports_realtime=True,supports_speech_to_speech=True,
        supports_interruption=True,supports_tool_use=True,
        default_model='gpt-live-1.1',default_voice='sol',
    )
    def run_text(self,request,recorder):
        raise ProviderUnavailable('codex_live is a native realtime-agent connection, not a TTS renderer')
