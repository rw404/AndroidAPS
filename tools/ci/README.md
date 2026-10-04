# Signed validation build

The feature branch's `Branch CI` workflow is a manual, artifact-only build. Its
existing eight `buildVariant` choices are retained. Two Healfi choices are added;
`healfiRelease` is the default and `healfiDebug` is available for local testing.
Healfi uses `app.healfi.androidaps` and installs alongside the original AAPS.
The `full` variant still uses `info.nightscout.androidaps`. Dispatch it with the feature branch as the workflow ref.
It does not access Google Drive, delete previous APKs, create releases or change
the stable branch's workflow.

## Current build

GitHub REST writes and Actions dispatch work. The feature branch
`improve/quiet-pump-overview` has been published, and
[run 37212384583](https://github.com/rw404/AndroidAPS/actions/runs/37212384583)
is building **healfiRelease** from source commit
`7545cba1bf53ea85f4aa04a7cda2a6b9818d2b70`. At the time of this check the run is
in progress; a signed release APK is not yet a verified output. Download it only
after the run succeeds and confirm its source SHA in `validation.json`.

The local **HealfiDebug** APK from the same source commit has passed package,
signature, bundled-asset and alignment checks, and 572 JVM tests have passed.
Its package is `app.healfi.androidaps`, so it installs separately from the
existing AAPS. Physical phone, sensor, radio bridge, pump operation and clinical
response remain unverified.

Earlier Git push and connected-API attempts returned HTTP 403; those failures
describe the initial credentials, not the current working REST connection.
No additional access request is needed for this dispatched build.

## Reproduce the feature branch

For a new checkout, apply the delivered patch to the baseline commit, publish
the feature branch if needed, and dispatch the existing registered workflow on
that ref:

```sh
git switch -c improve/quiet-pump-overview
git am /path/to/androidaps-improvements.patch
git push origin improve/quiet-pump-overview
gh workflow run branch-ci.yml --repo rw404/AndroidAPS \
  --ref improve/quiet-pump-overview -f buildVariant=healfiRelease
```

The delivered patch starts at commit
`48f9f6c7c2f9bc5c02ff873845d980d20a6ff005`. Use a clean checkout of that commit
before these commands. A later checkout with overlapping changes requires a
reviewed rebase instead of blindly applying the patch. Download the artifact only
from the successful run whose source commit matches the feature branch. No merge
into the stable branch is needed to build it.

The workflow tests native food parsing/calculation, profile-based bolus
calculation, the command queue (including repeated status/SMB/cancellation
requests), and the Medtronic and RileyLink drivers. These are JVM tests; they do
not exercise a phone, sensor, radio bridge, physical pump or clinical response.

Signing uses the repository's existing `KEYSTORE_SET` secret (base64 of
`keystore_base64|store_password|key_alias|key_password`) or the four separate
`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD` secrets.
Decoded values are masked before use. The keystore, credentials and temporary
Gradle properties have mode 0600 under a mode-0700 directory in `RUNNER_TEMP`.
An isolated Gradle user home reuses only the normal dependency-cache and wrapper
directories; signing properties are absent from shell commands and the ordinary
user-home properties file. Build/configuration caches are disabled. An `always`
step removes the private temporary signing directory and AGP-generated
`signing-config-data.json` files that contain signing passwords. These files
are outside the upload allowlist.

Each artifact is named with its selected variant and full source commit. It
contains a uniquely named APK, `validation.json`, sanitized `junit.xml` and
`tests.json`. APK upload follows successful tests, signature verification,
certificate comparison with the configured keystore, package identity, ZIP CRC,
bundled-food-asset hashes, 16 KiB ZIP/native-library alignment, and source checks.
Healfi also checks the compiled launcher label, private provider authorities,
unique Wear permission and absence of the original OAuth callback schemes.
Failed builds upload available test reports only. Raw Gradle logs, environment,
keystore and credentials are never included in the artifact allowlist.

`validation.json` records the source commit, Actions run URL, APK SHA-256 and
public signer-certificate SHA-256. A match with the configured Actions keystore
proves which key signed the candidate. It does **not** by itself prove that the
phone's installed APK has the same certificate. Compare the certificate against
the original APK used to install that app, or against the installed APK obtained
from the phone. The unchanged full package is `info.nightscout.androidaps`,
version 3.4.2.6, versionCode 1500. Android can update the existing installation
only with a compatible signing identity; do not uninstall the current therapy
app to work around a signature mismatch. Healfi has a different package and
does not require the original AAPS signing key for a separate first installation.
Updates of Healfi itself require a compatible Healfi signing identity. Both apps
have separate private data; installing Healfi does not copy the AAPS profile or
therapy history. Importing settings is an explicit operation and can activate the
original pump driver, remote controls and cloud destinations. Only one app should
control the physical pump at a time; separate packages do not enforce that rule.

For local reproduction, provide a private `RUNNER_TEMP` directory and
`AAPS_BUILD_VARIANT=healfiRelease`, then run `prepare`, `test`, `build`, `reports`,
and `verify` with `python3 tools/ci/signed_validation.py`. The verification step
also requires the public `GITHUB_SHA`, `GITHUB_REPOSITORY`, and `GITHUB_RUN_ID`
metadata. Always run `cleanup` afterward. JDK 21, Android SDK 36 and Android
build tools 35.0.0 are required. No passwords need to be passed on the command
line or copied into the repository.

## Apply and dispatch from an authenticated workstation

If the current automation credential cannot push or dispatch this repository,
apply the supplied patch in a local checkout with an account that can. Keep the
changes on their feature branch; the stable branch need not be merged or changed.
Do not dispatch the stable branch's existing workflow because it uploads to
Google Drive and can replace previous APKs.

From the checkout that already contains these commits:

```sh
git push origin HEAD:improve/quiet-pump-overview
gh workflow run branch-ci.yml --repo rw404/AndroidAPS \
  --ref improve/quiet-pump-overview -f buildVariant=healfiRelease
gh run list --repo rw404/AndroidAPS --workflow branch-ci.yml --limit 5
gh run watch RUN_ID --repo rw404/AndroidAPS
gh run download RUN_ID --repo rw404/AndroidAPS --dir signed-validation
```

Replace `RUN_ID` with the new manual run's ID and confirm its source SHA matches
the feature branch commit before using its candidate. The Actions UI provides
the same branch/variant selection and artifact download. These commands do not
require downloading the private signing key or sending any password here.
