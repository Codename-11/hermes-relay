# sherpa-onnx and English keyword-spotting model

Hermes-Relay's optional experimental Android wake-word feature uses:

- **sherpa-onnx v1.13.4**, Copyright 2024 Xiaomi Corporation and sherpa-onnx
  contributors, licensed under the Apache License 2.0:
  <https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.4/LICENSE>
- **sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01**, published by the
  sherpa-onnx project for English keyword spotting. The upstream model listing
  identifies the model as Apache License 2.0:
  <https://www.modelscope.cn/models/pkufool/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01/summary>

The application downloads only the int8 encoder, decoder, int8 joiner, and
token table when the user explicitly enables wake word. Downloads use the
publisher's `resolve/master` URLs and are pinned in
`WakeWordModelInstaller.kt` by byte length and SHA-256:

| File | Bytes | SHA-256 |
|---|---:|---|
| `encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx` | 4,807,159 | `1e721676515bcd42a186979733981213c66c80db680e1cc582dfedf3be76e678` |
| `decoder-epoch-12-avg-2-chunk-16-left-64.onnx` | 1,063,189 | `f61ebd3eed3773a44d088d53dfae92dbb6aec4839f4dcaee2d402414741663a3` |
| `joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx` | 163,380 | `eae9da0c7e1e6c6a3f4cc42d167899c388f6c6701b94cb96320e4f55df79624c` |
| `tokens.txt` | 5,006 | `fd2ded4050a55d2b1578870ba8697d02371980217806b7558bd0a5cc60f3ba53` |

The sherpa-onnx project notes that model licenses can differ from the runtime
license. Redistribution and production release of these weights must therefore
retain this provenance record and be reviewed independently of the
sherpa-onnx runtime dependency.

## Apache License 2.0

Licensed under the Apache License, Version 2.0 (the "License"); you may not use
these works except in compliance with the License. You may obtain a copy at:

<https://www.apache.org/licenses/LICENSE-2.0>

Unless required by applicable law or agreed to in writing, software distributed
under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
CONDITIONS OF ANY KIND, either express or implied. See the License for the
specific language governing permissions and limitations under the License.
