# Aurora Pure 1.4.1

Fixes three defects that shipped in 1.4.0.

> Still signed with the key introduced in 1.4.0. Coming from 1.3.0 or earlier,
> uninstall first. Coming from 1.4.0, this installs over it normally.

## Opening an app by link no longer hangs

A Play link, a shared listing, or a pasted package name opened a details screen that
loaded forever. The metadata request actually succeeded in under two seconds; a guard
meant to stop a slow response from overwriting a different app also discarded the very
first response, because nothing was on screen yet to compare it against. The guard now
tracks the package the user asked for rather than the one already displayed.

## Scan progress reads honestly again

- The pull-to-refresh indicator stayed latched on for the whole scan, hovering over the
  first result. It now retracts after the gesture; the running scan is reported by the
  progress strip above the list.
- The progress bar sat at a truthful but discouraging 0% until the first delivery path
  completed, roughly twenty seconds in, which looked like a hang. Until something has
  actually come back it is indeterminate, which is what is true, and switches to a real
  fraction once paths start completing.

## Documentation and tests

- All README screenshots are regenerated from this build. The previous set still showed
  the 1.3.0 interface.
- The CLI help test no longer depends on whether the environment supports ANSI. Picocli
  styles usage help when it detects a terminal, which split the command name across
  escape sequences and failed the assertion anywhere the test ran with a TTY.

---

