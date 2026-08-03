# Changelog

All notable changes to Coil are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versioning
follows [Semantic Versioning](https://semver.org/). Release notes on GitHub Releases are extracted
automatically from the `## [x.y.z]` heading matching `versionName` in `app/build.gradle.kts` — see
`.github/workflows/release.yml`. Keep headings exact for that to keep working.

## [Unreleased]

### Added
- Project scaffolding: architecture and protocol documentation (`docs/`), brand assets (`brand/`),
  Android theme reference bundle (`android/`), static UI mockup (`mockup/`)
- Transport validation: standalone JeroMQ spike (`spike/`) and Python protocol probe
  (`tools/probe_phoniebox.py`) against a live Phoniebox v3 box
- CI/CD scaffolding: build/test/lint, CodeQL, release, Google Play deploy, and GitHub Pages
  workflows (`.github/workflows/`), gated to no-op until the `:app` module exists
