# Raymond PD reference recording

An excerpt of a real SDRTrunk baseband recording of a clean Raymond PD call, with an adjacent P25 system also
in the recording. `P25ReferenceRecordingTest` (`raymondPD*`) decodes it with both C4FM decoders and checks the expected results below.

## Channel

| | |
|---|---|
| System / channel | Municipalities / Raymond / Raymond PD |
| Frequency | 155.640 MHz |
| Protocol | P25 Phase 1, C4FM, conventional |
| NAC | `0x860` (2144) |
| Encryption | **Never.** Any LDU2/HDU that says "encrypted" is a bit-error artifact. |
| Playlist decode config | `modulation="C4FM"`, `configuredNAC="2144"`, `maxBchErrors="5"` |

## Recording format

SDRTrunk channel baseband recording: WAV, 2 channels (I/Q), 16-bit signed, 50 kHz, centred on 155.640 MHz.
The excerpt was cut from the original file without resampling.

## `good-antenna_20261111_202316_10-20s_155640000_baseband.wav`

- **Source:** `20261111_202316_155640000_baseband.wav`, 10.0 to 20.0 s (from `Municipalities_Raymond_Raymond-PD_1244.rar`).
- **Hardware:** tuner `RTL-2832/R820T 90000109`.
- **Signal:** about 14 dB over the noise floor in a ±5 kHz band. 0% clipping. Frequency offset about −160 Hz.
  Outer-symbol deviation about 1865 Hz.
- **Content:** one call with two keyups, about 3.0 to 5.2 s and about 5.9 to 7.4 s into the excerpt.
- **Interferer:** an adjacent P25 system, NAC `0x117`, on 155.625 MHz (−15 kHz) at about 24 dB, active about
  1 to 5 s into the excerpt. It must never appear in the decode.

Expected results. They were identical on every build tested: STABLE f2998fa7 C4FM, UPSTREAM 72864f24 C4FM and
C4FM_V2, and the STABLE demodulator ported onto UPSTREAM. Every build was rated **5/5** by ear.

| Metric | Value |
|---|---|
| Valid LDUs | 21 (test floor 20) |
| Valid HDUs | 2 |
| LDU2 with valid, clear encryption sync | 8 |
| LDU2 with valid encryption sync saying "encrypted" | 0 |
| Messages with a NAC other than 0x860 | 0 |

The decoders also emit 2 to 3 trailing LDUs after the signal drops, at about 7.4 s. These are flywheel frames
with garbage encryption sync. They count as valid LDUs today.

## Lessons learned (2026-11-11 investigation)

**Symptom:** after the move to the UPSTREAM merge, Raymond PD decoded as garbled digital audio with many false
"encrypted" flags, which looked like a decoder regression.

**Cause: signal, not software.** Raymond PD's preferred tuner, `RTL-2832/R828D 201`, sits on a different antenna
from `R820T 90000109`. On 201 the channel peaked at only 8 to 10 dB over the noise floor. Swapping the two tuners'
channel assignments in the playlist raised it to about 14 dB. That gave a perfect decode on every build, the
recording above. Before suspecting the decoder, measure the signal level of a baseband recording.

Rough guide for P25 C4FM on these captures:

| Signal over noise floor (±5 kHz) | Result |
|---|---|
| about 14 dB | Clean decode, every voice block fully corrected |
| 8 to 10 dB | Frames still sync and the NAC mostly reads, but voice is unreadable. About 35% of NIDs need correction, about 15% decode to an invalid DUID, and about 40% of LDU2s carry false "encrypted" flags that fail their RS check. |

**Settings that did not matter.** `configuredNAC` made no measurable difference on either C4FM decoder. Relaxing
the UPSTREAM C4FM sync gates (`isNoisy`, optimized/equalized thresholds, waveform validation) added only noise frames.

**Revisit if a weak signal is being troubleshot.** On the 8 to 10 dB capture, UPSTREAM's rewritten C4FM
demodulator recovered much less than STABLE's. It produced about 40% fewer usable voice frames on the full
367 s recording `20261111_195407`. Usable means IMBE frames that JMBE played without muting or repeating,
scored by chaining `IMBEFrame.getModelParameters()` the way the codec does.

| Build | IMBE frames | Usable |
|---|---|---|
| STABLE C4FM | 2106 | 1176 |
| UPSTREAM C4FM | 1593 | 732 |
| UPSTREAM C4FM_V2 | 2502 | 1193 |
| STABLE demodulator ported onto the UPSTREAM framer | 2043 | 1120 |

None of these were intelligible, so this was not pursued. The gap is in the demodulator: STABLE's demodulator
on UPSTREAM's framer recovers about 95% of STABLE. If a marginal channel matters later, compare
`P25P1DemodulatorC4FM` at `f2998fa7` against `P25P1SymbolProcessorC4FM`, or switch that channel to C4FM_V2.
The weak recordings were not committed, because they don't contain intelligible voice.

## Running

```
./gradlew test --tests '*P25ReferenceRecordingTest'
```

The build needs a Bellsoft Liberica JDK 27 **Full** (with JavaFX), for example
`-Porg.gradle.java.installations.paths=$HOME/.jdks/dl-liberica27/jdk-27.0.1.0.3-full`.
