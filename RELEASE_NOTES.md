# Aurora Pure 1.4.2

Fixes four defects in the desktop CLI, found by running the shipped build rather than
reading it. The Android app is unchanged from 1.4.1 apart from its version.

> Still signed with the key introduced in 1.4.0. Coming from 1.3.0 or earlier, uninstall
> the Android app first. Coming from 1.4.0 or 1.4.1, it installs over the top normally.

## The CLI reports the version it is actually running

`aurora-pure --version` and the interactive banner both said 1.3.0 while the build was
1.4.1. The version had been typed into `Main.kt` and into all four message bundles, so a
release had to remember five places and nothing checked them. It is generated from the
project version now, and a test fails if it ever reads back as unknown.

## --language is no longer ignored

Asking for a language other than the machine's did nothing on any non-English system:
`--language en` on a Korean or Japanese machine still printed Korean or Japanese.
`ResourceBundle` consults the JVM default locale before falling back to the base bundle,
so the requested language never got a chance. It is the only locale consulted now.

## Translated output is readable on Windows

Every Korean, Japanese, and Chinese message the CLI ships printed as question marks,
because the launcher let the JVM fall back to a legacy code page. The start scripts pin
UTF-8 for standard output and standard error.

## A closed stdin is no longer taken as consent

The download prompt read end-of-stream as "yes", so a piped or closed stdin started a
transfer nobody agreed to. It is a refusal now; `--yes` remains the way to skip the
prompt deliberately. The same flow also opened two readers over stdin, and the first
swallowed whatever followed the line it returned — with piped input the answer to the
download prompt disappeared into the reader that asked which variant to use. One shared
reader serves both prompts.

## Carried across from the Android app

- An HTTP 429 renews the anonymous session and retries with a jittered backoff instead of
  failing the scan. A path still throttled afterwards stops on its own, and the scan warns
  that its result is partial rather than presenting a subset as the whole matrix.
- Protocol requests have a call timeout, and the protocol and download clients share one
  connection pool and dispatcher.
- A scan no longer re-hashes each cached base APK, or re-opens and re-parses its ZIP twice,
  once per probe. Both results are memoised under the delivered content identity.
- Download progress reports transfer speed and remaining time.
- The variant table measures its columns across the whole list and accounts for
  double-width CJK glyphs, so rows line up in every language. The Universal/Combined tag
  moved into its own column instead of stretching the version column.

---

