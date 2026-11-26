# Decode regression corpus

23 baseband I/Q captures (91.9 MB, 50 kHz stereo PCM) used to prove that a code change did not
degrade decode quality. `./gradlew runRegression` decodes the whole corpus and compares against the
committed baseline, exiting non-zero if anything got worse.

## Running it

```bash
./gradlew runRegression -Pjmbe=/path/to/jmbe-1.0.9.jar
```

Without `-Pjmbe` the decode metrics still run, but every audio metric reads zero — the JMBE codec is
what turns IMBE frames into audio. Supply it whenever the audio gates matter.

To accept a change as the new gold standard — only after confirming the deltas are genuine
improvements:

```bash
./gradlew runRegression -Pjmbe=... -Pupdate
```

## What the gates are

Two consecutive runs of identical code over this corpus were byte-identical on every decode counter.
The only field that drifts is `quality_score` (waveform artifact analysis, ~0.5% run to run). So the
gated metrics carry **zero tolerance**:

| Metric | Rule |
|---|---|
| `ldu_count` | must not decrease. Voice LDUs only: a TDU that DUID correction rewrote as an LDU is reported as `corrected_ldu`, because the live audio module silences it |
| `valid_messages` | must not decrease |
| `audio_seconds` | must not decrease by more than 1% |
| MDC CRC-valid bursts (per file) | must not decrease |

The baseline was last reset on 2026-11-25, when the audio module started suppressing LDUs that carry no recoverable
voice (`P25P1AudioModule.isUnrecoverable`): LDUs whose corrected IMBE frames total 72+ errors with fewer than 3 clean
frames, and LDUs that the framer truncated (dispatched with zero padding because the next frame's sync arrived early).
`ldu_count` and `valid_messages` did not change. `audio_seconds` fell on 11 files (most on the simulcast clips), because
JMBE 1.0.9, which the gate runs with, synthesizes every frame, so the suppressed frames had been counted as audio.
Spectrograms of the largest drops (`p25-c4fm-a_03`, `p25-lsm-a_03`) showed that what was removed were broadband clicks,
tonal blobs and a click train, with the speech intact. The audio metrics skip DUID-corrected and unrecoverable LDUs,
as the live audio module does. The previous reset, on 2026-11-22, was when `ldu_count` stopped counting corrected TDUs.

Everything else — `corrected_ldu`, `sync_blocked`, `bit_errors`, `sync_losses`, `total_messages`, `audio_segments`,
`quality_score` — is printed with its delta but never fails the run. Read those: they are how you
tell a real improvement from a shift in behaviour.

## Anonymization

These are real off-air recordings. Everything that identified a licensee is gone:

- **Directory and file names** carry a generic channel id, never a department or site.
- **Frequencies are synthetic** (`100000001`–`100000009`). They exist only as the join key that
  `DecodeQualityTest.extractFrequency` uses to match a capture to its channel config (it reads the
  third underscore-separated token of the filename). They are not real allocations.
- **Capture timestamps are dropped** from the filenames.
- `corpus-playlist.xml` carries generic `name`/`system`/`site` and no tuner serial.

What is kept is what the decoder needs: modulation, NAC, and bandwidth. The mapping back to the real
channels is deliberately not in this repository.

What anonymization does **not** cover, stated plainly rather than pretended away: everything carried
in the transmission itself survives, because it is the thing under test. NAC values are real — a P25
decoder cannot lock without the NAC that is actually in the RF. MDC unit IDs appear in
`baseline/mdc.txt` because they are what the decoder recovered, and they are recoverable from the
samples by anyone who runs the decoder regardless. The audio payload is real dispatch traffic;
synthetic signals would not exercise the failure modes these clips were chosen for. Scrubbing the
file naming keeps the corpus from advertising who it belongs to — it does not make the RF anonymous.
Treat the corpus as public the moment the repository is.

Note that `corpus-playlist.xml` reproduces each channel's `decode_configuration` attributes verbatim
so the tuning constants travel with the corpus. `DecodeQualityTest` itself only reads `modulation`,
`configuredNAC`, and `frequency` from the playlist — the CMA/BCH/DFE values come from harness
defaults — but keeping them makes the intended live configuration explicit.

## Coverage

| Channel | Decoder | NAC | Files | Why it is here |
|---|---|---|---|---|
| `p25-lsm-a` | P25P1 CQPSK_V2 | 279 | 4 | **Simulcast.** The only LSM material we have, and the primary sensor for anything touching the equalizer, PLL, or channel bandwidth. Spans a 7 s burst through a dense 25 s clip with 1531 blocked syncs. |
| `p25-c4fm-a` | P25P1 C4FM | 1618 | 3 | Burstiness range: the worst waveform quality in the corpus (0.358), a 75%-silence sparse capture, and the burstiest file (14 audio segments). |
| `p25-c4fm-b` | P25P1 C4FM | 880 | 3 | A clean single transmission, a marginal 13%-decode-ratio file at 50% artifact, and a long high-artifact clip. |
| `p25-c4fm-c` | P25P1 C4FM | 2087 | 2 | Includes a capture with **no P25 traffic at all** — a false-positive guard. If a change starts decoding LDUs here, it is inventing them. |
| `p25-c4fm-d` | P25P1 C4FM | 827 | 2 | Short/high-silence and long/continuous ends of the same channel. |
| `p25-c4fm-e` | P25P1 C4FM | **0 (auto)** | 2 | NAC auto-detect, which exercises `NACTracker`. One dense short burst, one long sparse capture. |
| `p25-c4fm-f` | P25P1 C4FM | 2311 | 1 | Mid-length with a 12% artifact rate. |
| `p25-c4fm-g` | P25P1 C4FM | 2144 | 2 | **Unrecoverable voice.** `_01` is a unit received at ~4 dB in-band SNR: sync, NAC and link control decode, but every voice frame is near-random bits, and the call must produce **no audio** (it used to come out as clicks half a second apart). `_02` is clean calls at ~12 dB with a truncated phantom LDU2 at call start and a 138-bit LDU stub at call end, which must be dropped while the speech is kept. The gate can't see junk audio return on a zero baseline, so `UnrecoverableVoiceCorpusTest` checks the LDU classification of both clips. |
| `nbfm-mdc-a` | NBFM 12.5 | — | 2 | MDC-1200 bursts for the dual-branch diversity decoder. |
| `nbfm-mdc-b` | NBFM 12.5 | — | 2 | More MDC-1200, including the richest file in the set (4 CRC-valid bursts). |

The MDC evaluator runs over the **whole** corpus, not just the NBFM channels. The 19 P25 files all
baseline at zero CRC-valid bursts, so they double as a false-positive guard on the MDC detector.

## Provenance

Clips were selected from a 614 MB pool of activity-triggered captures by profiling every file with
`runDecodeScore` and `runMdcBatch`, then picking a spread across transmission length, burst density,
and signal quality. Long captures were trimmed to 25 s; every trimmed clip was re-profiled to confirm
it kept its signal. One (`p25-c4fm-e_02`) had its traffic past the 25 s mark and uses a 25–50 s
window instead. MDC clips were verified to retain all CRC-valid bursts after trimming.

The `p25-c4fm-g` clips were added on 2026-11-25 from a later set of activity-triggered captures, chosen by replaying
each capture through the live processing chain (`runPipelineReplay -Pplaylist=... -Pchannel=... -PaudioOut=...`, which
also writes per-frame IMBE error counts) and measuring each call's in-band SNR. `_01` is the first 25 s of a 39 s
capture and keeps the whole call; `_02` is untrimmed.

`corpus.json` records the per-file duration, size, decoder config, and the character each clip was
chosen for.
