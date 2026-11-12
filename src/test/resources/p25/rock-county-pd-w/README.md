# Rock County PD W reference recording

An excerpt of a real SDRTrunk baseband recording of a strong, clean Rock County PD W transmission.
`P25ReferenceRecordingTest.rockCountyPDWCQPSK` decodes it and checks the expected results below.

## Channel

| | |
|---|---|
| System / channel | Rockingham County / Law Enforcement / Rock County PD W |
| Frequency | 154.815 MHz |
| Protocol | P25 Phase 1 (decoded as CQPSK/LSM) |
| NAC | `0x117` (279) |
| Encryption | Never seen. Treat any "encrypted" LDU2/HDU as a bit-error artifact. |
| Tuner | `RTL-2832/R820T 90000109` |
| Playlist decode config (2026-11-11) | `modulation="CQPSK_V2"`, `configuredNAC="279"`, `maxBchErrors="5"`, `cmaAcquisitionMu="0.003"`, `cmaTrackingMu="0.001"`, `cmaGearShiftMs="200"` |

## `rock-county-pd-w_20261111_204909_2.5-18.5s_154815000_baseband.wav`

- **Source:** `20261111_204909_154815000_baseband.wav` (44.0 s), 2.5 to 18.5 s.
- **Format:** WAV, 2 channels (I/Q), 16-bit signed, 50 kHz, centred on 154.815 MHz. Cut without resampling.
- **Signal:** about 31 to 33 dB over the noise floor in a ±5 kHz band. 0% clipping. Centroid within about 200 Hz.
  No significant adjacent-channel energy.
- **Content:** one continuous voice transmission starting about 0.5 s into the excerpt.

### Expected results (CQPSK, `P25P1DecoderLSM`)

The same on the old build (`upstream-merge-2026-10`, f2998fa7) and the merge candidate (`upstream-merge-2026-11`).

| Metric | Value |
|---|---|
| Valid LDUs | 84 (test floor 80) |
| Valid HDUs | 1 |
| LDU2 with valid, clear encryption sync | 36 (test floor 35) |
| LDU2 with valid encryption sync saying "encrypted" | 0 |
| Garbage LDUs (9 IMBE frames needing ≥ 90 bit corrections) | 0, checked only when `JMBE_PATH` is set |

## Known issue: CQPSK_V2 decodes bursts of garbage on this signal

Listeners heard Rock County PD W as "mostly fine, then every so often very distorted". The cause is the
channel's **CQPSK_V2** decoder (`P25P1DecoderLSMv2`), not signal and not the build. In both the old build and
the merge candidate, it periodically emits LDUs that decode as pure noise. Each one needs 108 IMBE bit
corrections, the maximum, at the same moments in the recording. Plain CQPSK decodes those moments cleanly.
The likely cause is the LSMv2 CMA equalizer briefly losing lock, but this hasn't been confirmed.

Listening scores on the full 44 s recording (2026-11-11):

| Build | Decoder | Rating | Voice frames | Usable | Garbage LDUs |
|---|---|---|---|---|---|
| old | CQPSK_V2 | 2/5, "middle is entirely distorted" | 1539 | 855 | 11 |
| candidate | CQPSK_V2 | 2/5, "materially similar" | 1593 | 886 | 12 |
| old | CQPSK | **5/5**, "almost perfect, two quick blips at the end" | 1350 | 995 | 0 |
| candidate | CQPSK | 4/5, "similar, slight clicking in the background" | 1512 | 1087 | 0 |

"Usable" counts IMBE frames JMBE played without muting or repeating, scored by chaining
`IMBEFrame.getModelParameters()` the way the codec does. The candidate's CQPSK decodes about 160 more frames
than the old build's, mostly 28 to 31 s into the recording, where the old build decoded nothing. The
listener heard no extra speech there, and that stretch is the likely source of the clicking. It hasn't been
isolated.

On this excerpt, CQPSK_V2 decodes 90 to 91 valid LDUs, 8 to 9 of them garbage, on both builds.

**Second CQPSK_V2 bug, a crash.** `P25P1DemodulatorLSMv2.process()` throws `ArrayIndexOutOfBoundsException`
when a sample buffer is shorter than the previous one. It shrinks `mBufferI`/`mBufferQ` with `Arrays.copyOf`
before copying the reserve samples out of them. The code is identical on both builds. On the candidate it
triggers on the short final buffer of this excerpt, which is why CQPSK_V2 isn't tested here.
