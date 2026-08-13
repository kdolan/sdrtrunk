# Decode regression corpus

21 baseband I/Q captures (82.6 MB, 50 kHz stereo PCM) used to prove that a code change did not
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
| `ldu_count` | must not decrease |
| `valid_messages` | must not decrease |
| `audio_seconds` | must not decrease by more than 1% |
| MDC CRC-valid bursts (per file) | must not decrease |

Everything else — `sync_blocked`, `bit_errors`, `sync_losses`, `total_messages`, `audio_segments`,
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
| `nbfm-mdc-a` | NBFM 12.5 | — | 2 | MDC-1200 bursts for the dual-branch diversity decoder. |
| `nbfm-mdc-b` | NBFM 12.5 | — | 2 | More MDC-1200, including the richest file in the set (4 CRC-valid bursts). |

The MDC evaluator runs over the **whole** corpus, not just the NBFM channels. The 17 P25 files all
baseline at zero CRC-valid bursts, so they double as a false-positive guard on the MDC detector.

## Provenance

Clips were selected from a 614 MB pool of activity-triggered captures by profiling every file with
`runDecodeScore` and `runMdcBatch`, then picking a spread across transmission length, burst density,
and signal quality. Long captures were trimmed to 25 s; every trimmed clip was re-profiled to confirm
it kept its signal. One (`p25-c4fm-e_02`) had its traffic past the 25 s mark and uses a 25–50 s
window instead. MDC clips were verified to retain all CRC-valid bursts after trimming.

`corpus.json` records the per-file duration, size, decoder config, and the character each clip was
chosen for.
