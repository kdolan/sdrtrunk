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

## Collection: long keyups, short bursts, rapid exchanges, off-frequency (2026-11-21)

24 excerpts picked from 5,070 activity-triggered recordings made between 2026-11-11 and 2026-11-21 (33 GB, not kept).
Same channel, tuner and format as above. Each excerpt keeps the original recording from 0.5 s before the first keyup to
1.0 s after the last one, so most of the recorder's pre-roll and trailing holdover is cut. Nothing is resampled. The name
gives the source recording's start time and the cut's start and end within it, in seconds.

Selection, from the scan of every recording:

- **long, short, rapid** come only from 2026-11-12 to 2026-11-19, while the channel was healthy: an SNR of at least
  25 dB (18 dB for the short ones), a spectral centroid within ±250 Hz, call events logged, and nothing encrypted.
  Each category has one recording per day where possible.
- **long:** the longest single keyups (28 to 34 s continuous).
- **short:** every keyup 2.6 s or shorter. Isolated keyups shorter than about 2 s are rare on this channel.
- **rapid:** at least 5 speaker changes in under 60 s with gaps as short as 0.3 s, mostly the dispatcher console
  (`8000x`) trading with 1 or 2 units.
- **off-frequency:** from the 2026-11-21 outage (see below).

Keyups are counted from 0.1 s power blocks more than 10 dB above the recording's floor, with gaps shorter than 0.3 s
merged. Radios and speaker changes come from SDRTrunk's call event log for the same time span. LDUs are valid LDUs
decoded by CQPSK (`P25P1DecoderLSM`) on master `635888d1` with no frequency shift.

| File | Length s | Keyups | Longest s | Shortest s | Radios | Speaker changes | SNR dB | Valid LDUs |
|---|---|---|---|---|---|---|---|---|
| `long/long_20261112_130208_2.5-37.5s_154815000_baseband.wav` | 35.0 | 1 | 33.5 | 33.5 | 2 | 3 | 30 | 192 |
| `long/long_20261113_114157_2.9-46.0s_154815000_baseband.wav` | 43.1 | 2 | 32.5 | 0.4 | 2 | 2 | 29 | 187 |
| `long/long_20261114_202546_0.3-57.1s_154815000_baseband.wav` | 56.8 | 4 | 29.1 | 3.5 | 2 | 6 | 30 | 296 |
| `long/long_20261115_154857_0.0-37.3s_154815000_baseband.wav` | 37.3 | 2 | 32.9 | 3.0 | 3 | 5 | 29 | 102 |
| `long/long_20261116_124620_0.2-35.5s_154815000_baseband.wav` | 35.3 | 1 | 33.8 | 33.8 | 2 | 1 | 29 | 195 |
| `long/long_20261117_194143_1.9-40.4s_154815000_baseband.wav` | 38.5 | 2 | 32.6 | 3.2 | 2 | 3 | 29 | 206 |
| `long/long_20261119_160807_3.0-47.8s_154815000_baseband.wav` | 44.8 | 2 | 32.2 | 7.6 | 3 | 5 | 29 | 231 |
| `short/short_20261112_194046_2.9-13.3s_154815000_baseband.wav` | 10.4 | 2 | 2.4 | 2.3 | 1 | 0 | 29 | 31 |
| `short/short_20261113_172118_1.3-3.6s_154815000_baseband.wav` | 2.3 | 1 | 1.8 | 1.8 | 1 | 0 | 28 | 10 |
| `short/short_20261114_040030_1.8-5.8s_154815000_baseband.wav` | 4.0 | 1 | 2.5 | 2.5 | 1 | 0 | 29 | 16 |
| `short/short_20261115_224411_1.3-5.4s_154815000_baseband.wav` | 4.1 | 1 | 2.6 | 2.6 | 1 | 0 | 27 | 17 |
| `short/short_20261116_090436_2.9-6.8s_154815000_baseband.wav` | 3.9 | 1 | 2.4 | 2.4 | 1 | 0 | 28 | **0** |
| `short/short_20261117_065750_3.0-9.5s_154815000_baseband.wav` | 6.5 | 2 | 2.3 | 2.2 | 3 | 2 | 29 | **0** |
| `short/short_20261118_224522_2.9-6.9s_154815000_baseband.wav` | 4.0 | 1 | 2.5 | 2.5 | 1 | 0 | 29 | 16 |
| `rapid/rapid_20261112_182227_2.9-48.0s_154815000_baseband.wav` | 45.1 | 5 | 12.4 | 0.5 | 3 | 6 | 30 | 190 |
| `rapid/rapid_20261113_090154_2.9-33.1s_154815000_baseband.wav` | 30.2 | 5 | 6.4 | 2.7 | 2 | 5 | 29 | 146 |
| `rapid/rapid_20261114_150713_2.9-29.9s_154815000_baseband.wav` | 27.0 | 4 | 8.8 | 3.2 | 2 | 5 | 29 | 135 |
| `rapid/rapid_20261115_235615_3.0-29.6s_154815000_baseband.wav` | 26.6 | 4 | 7.1 | 3.5 | 3 | 5 | 29 | 131 |
| `rapid/rapid_20261116_200154_3.0-39.9s_154815000_baseband.wav` | 36.9 | 5 | 13.3 | 3.4 | 3 | 6 | 28 | 196 |
| `rapid/rapid_20261118_180405_0.0-42.5s_154815000_baseband.wav` | 42.5 | 6 | 17.8 | 0.2 | 3 | 5 | 26 | 159 |
| `rapid/rapid_20261119_120004_2.9-35.1s_154815000_baseband.wav` | 32.2 | 5 | 11.3 | 2.1 | 3 | 6 | 29 | 129 |
| `off-frequency/off-frequency_20261121_060510_3.0-13.1s_154815000_baseband.wav` | 10.1 | 1 | 8.6 | 8.6 | 1 | 0 | 17 | 58 |
| `off-frequency/off-frequency_20261121_062841_3.0-7.3s_154815000_baseband.wav` | 4.3 | 1 | 2.8 | 2.8 | 1 | 0 | 17 | 22 |
| `off-frequency/off-frequency_20261121_095214_3.0-12.3s_154815000_baseband.wav` | 9.3 | 1 | 7.8 | 7.8 | 0 | 0 | 28 | **0** |

`P25ReferenceRecordingTest.rockCountyPDWCollectionCQPSK` checks a floor of about 90% of these LDU counts, and at most 2
"encrypted" LDU2s and 2 foreign-NAC messages (bit-error artifacts), for every recording except the two signalling bursts
and the off-frequency ones. `P25P1LSMFrequencyAmbiguityTest` uses the off-frequency recordings.

**Short bursts with no voice.** `short_20261116_090436` and `short_20261117_065750` decode 0 LDUs because they carry
no voice. They're signalling bursts, and the bench decoder (CQPSK) matches what the live system logged for each:

| Recording | Bench decode | Live decode (same channel, CQPSK) |
|---|---|---|
| `short_20261116_090436` | 141 TDU, 1 AcknowledgeResponse, 1 DenyResponse | about 105 TDUs from 09:04:36 to :38; voice from 80003 follows at 09:04:49, after the cut |
| `short_20261117_065750` | 275 TDU, 1 AcknowledgeResponse, 1 DenyResponse, 1 UnknownOSP, 1 invalid TDULC, 1 DroppedSamples | `ACK_RESPONSE_FNE FM:16777212 TO:866521 ACKNOWLEDGING:EMRG_ALRM_REQ`, `DENY_RESPONSE TO:866521 SERVICE:GRP_V_REQ`, about 270 TDUs, `RESERVED_23` OSP, a TDULC CRC failure and a dropped-samples event; voice from 80001 at 06:58:05 |

The `065750` burst is an emergency alarm from unit 866521 being acknowledged by the system.
`P25ReferenceRecordingTest.rockCountyPDWSignallingBursts` checks both bench decodes, and its Javadoc quotes the
relevant lines from the live decoded-message and call-event logs.

### Off-frequency: the 2026-11-21 outage and the CQPSK 4800 Hz false lock

After the nightly restart at 02:01 on 2026-11-21, nearly every C4FM channel on `RTL-2832/R820T 90000109` stopped
decoding. CQPSK channels on the same tuner (Rock County PD W and E) kept decoding. These recordings show why.

- `off-frequency_20261121_060510` and `_062841`: the signal sits about **+4.8 kHz** off centre. The channel's own
  correction has shifted the whole passband by about +4 kHz, so the noise floor spans −16 to +24 kHz instead of
  ±20 kHz. CQPSK decodes the signal **unshifted**, reporting a frequency error of only +33 to +413 Hz. It decodes about
  the same shifted back by −4.8 kHz.
- `off-frequency_20261121_095214`: after a manual PPM change and restart at 09:32, the signal sits about +2.5 kHz off.
  CQPSK decodes nothing unshifted and 48 LDUs when shifted −2.5 kHz.

**Cause: the CQPSK differential demodulator can't see an offset equal to the symbol rate.** 4800 Hz is exactly one
phase rotation per 4800 Hz symbol, which the differential demodulator can't detect. Shifting the clean 2026-11-20
12:00:16 recording by ±4800 Hz gives 92 and 108 valid LDUs (102 unshifted) and correction requests of −68 and −5 Hz.
Offsets close to ±4.8 kHz are pulled toward it (e.g. −4200 Hz requests −656 Hz, +5400 Hz requests −548 Hz). So the
channel frequency loop has stable false locks at ±4.8 kHz. A CQPSK channel in that false lock keeps decoding, and auto
PPM averages its corrections into the tuner. C4FM channels on the same tuner stop decoding beyond about ±2 kHz, and
they only report a frequency error after a valid sync, so they can't pull the tuner back.

**Fix (2026-11-21).** `DQPSKDifferentialDemodulator` keeps a wide-range carrier offset estimate: a power-weighted
average of the sample-to-sample phase change, unambiguous to ±half the sample rate. `P25P1DecoderLSM` moves its
reported offset by whatever multiple of 4800 Hz brings it nearest to that estimate. Simulcast tilt and noise move the
estimate by far less than 2400 Hz, so centred signals are unchanged. The ambiguity search also gains a ±2400 Hz step
(+2 and −2 are the same to the demodulator), which closes the 1.8 to 3.0 kHz dead zone where CQPSK never synchronized.
With the fix, the 2026-11-20 12:00:16 recording decodes at every shift from −6 to +6 kHz (84 to 108 LDUs) and requests
within 6% of the true correction. A closed loop that starts 4800 Hz off walks back to centre and keeps decoding.
`P25P1LSMFrequencyAmbiguityTest` covers this. Four of its five tests fail on the previous code, and the closed loop
there stays at +4902 Hz.

Open-loop frequency error requested by each decoder, from the reference recordings (a signal at +X Hz needs about −X):

| Shift | CQPSK (Rock W) | C4FM (Raymond) | C4FM_V2 (Raymond) |
|---|---|---|---|
| −2000 | no decode | +1249 (13 LDU) | +4166 (0 LDU, false syncs) |
| −1000 | +1002 | +1012 | +4095 |
| −500 | +506 | +522 | +2099 |
| +500 | −501 | −358 | −2110 |
| +1000 | −976 | −780 (20 LDU) | −4083 |
| +2000 | no decode | no decode | +2083 (0 LDU) |

C4FM_V2 overstates its correction request about 4×, a separate issue. No channel on this tuner uses C4FM_V2.
