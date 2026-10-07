# Contributing to the Straza approver app

The Straza approver app takes contributions as pull requests on GitHub, and the gates
every change passes are in the repository, so you can run them before you open one.
Everyone taking part follows the [code of conduct](CODE_OF_CONDUCT.md).

## Before you write code

Open an issue first for anything beyond a small fix: a bug you can reproduce, a behavior
you want changed, a feature you want added. Say what you observed, what you expected and
what you propose, so the design is agreed before the code exists. A typo, a broken link
or a one-line bug fix can go straight to a pull request.

## The Contributor License Agreement

Before we can merge your first pull request, you accept the
[Contributor License Agreement](CLA.md). You keep the rights in your work and grant
SynapTech s. r. o., the company that publishes Straza, a license to use it, including
under licenses other than the project's public ones. That is what keeps a commercial
license possible while the project stays open source. You accept once, and a new version
of the agreement asks you again.

These are the steps:

1. Read [CLA.md](CLA.md). The version in force is 1.2.
2. Check that the email address in each of your commits is added to your GitHub account.
   The check matches each commit to the person who accepted through that address, and a
   commit whose address belongs to no GitHub account cannot pass.
3. Open the pull request. The CLA check posts a comment with a link to the agreement.
4. Post this sentence as a comment of its own, copied exactly:

   I have read the Straza Contributor License Agreement version 1.2 and I accept it.

5. The check records your acceptance, turns green and confirms your acceptance in a
   comment that mentions you. If you opened the pull request and accepted version 1.2
   on an earlier one, the check turns green without a new comment.

The account that opens the pull request must have accepted, on this or an earlier pull
request. Every other person whose account a commit in it is linked to, or who is named
in a commit's co-author line, posts the sentence on this pull request, even after accepting
on an earlier one, so that nobody's earlier acceptance can cover commits that person did
not make. The email in each commit or line must be added to that person's GitHub
account. A co-author line that names an AI coding
tool, for example with the address noreply@anthropic.com, needs no acceptance. We keep
that line, because it tells us the tool was used, and you answer for that output as the
agreement's section 6 says.

The check reads the whole history of the pull request every time it runs, its comments,
review comments and reviews included, so an acceptance you posted while the check was busy
or down is recorded on its next run. A review or a review comment does not start the check
by itself, and the next comment or push does.

If your employer or a client may own what you write, get its written permission before
you accept, and say so in the pull request description. An entity that wants to sign for
its employees writes to hello@straza.ai and signs the agreement in written form with a
list of the people and GitHub accounts it authorizes.

A pull request stays unmerged until the check is green for every author. The check also
refuses a pull request whose title, description, commit messages or added lines, or whose
comments, review comments or reviews by its author or by anyone a commit names, contain
the words "Not a Contribution", in any case, because the agreement excludes material
marked that way. The definition of those words in the Apache License text is not read as a
marking.
We do not copy code from an issue, a discussion or an email whose author has not
accepted the agreement.
A Signed-off-by line is welcome, but it does not replace the agreement, because it
licenses your work only under the project's public licenses.

## What a change needs before it lands

The full check is green:

```sh
./gradlew :androidApp:lintFossDebug :androidApp:lintPlayDebug :shared:allTests \
  :androidApp:assembleFossDebug :androidApp:assemblePlayDebug
```

It lints both Android flavors, `foss` and `play`, runs the shared tests and builds both
flavors. Lint runs per flavor, because each flavor carries sources the other does not,
and a lint warning fails the build. When a change touches `shared/`,
`./gradlew :shared:compileKotlinIosArm64` is green too. It compiles the iOS code and runs
on any host. Linking the iOS framework, running the iOS tests and building the iOS app
need a Mac.

Cryptography, token and storage code is written test-first. A change to the hardware key
or to the encrypted storage also passes the instrumented tests on a physical Android
phone, `./gradlew :shared:connectedAndroidDeviceTest`, because an emulator always reports
a software key. Say in the pull request which phone model and Android version they ran
on.

Nothing secret enters the repository, ever: no signing key, no keystore password, no
token, no private key and no `google-services.json`. Both flavors build without any
secret.

The commit subject is imperative, says what changed and is at most 72 characters. The
why goes in the body, in sentences.

The pull request description says what changed and why, in sentences. It also names any
material you did not write yourself, with its source and license, and says whether an AI
tool produced any part of the change.

The app reads the server's approver API additively: it ignores fields it does not know
and never assumes that a field was removed. The enrollment QR payload and the approver
API belong to the [server repository](https://github.com/strazahq/straza), so a change to
either starts as an issue there, and the app follows once the server has published the
change.

## Where the gates live

You need JDK 21 and an Android SDK with `platforms;android-37.0` and `build-tools;37.0.0`.
Point `local.properties` at the SDK with `sdk.dir=/path/to/android-sdk`. The Gradle
wrapper checks the Gradle distribution against its pinned checksum, and
`gradle/verification-metadata.xml` pins the checksum of every dependency. The lint
settings are in `androidApp/build.gradle.kts` and `androidApp/lint.xml`, with no baseline
file.

The Gradle build defines every task: the lint, test and assemble tasks of the full check,
`:shared:compileKotlinIosArm64` for the iOS code, `:shared:connectedAndroidDeviceTest`
for the tests on a phone, and `:mock-strazad:run` for the development server. The Xcode
project for the iOS app is generated with `xcodegen --spec iosApp/project.yml`.

To try a change end to end, run `mock-strazad`, the strict development server, and pair
the app with it:

```sh
./gradlew :mock-strazad:run --args="8443"
adb reverse tcp:8443 tcp:8443
```

It speaks the app's server contract over real TLS, verifies every decision signature and
starts with work waiting. README.md describes it under "Try it against the mock server",
and the tests on a phone under "Test on a real Android phone".

## After the merge

The maintainer brings your commits into the project's working tree with your name, your
email address and your commit dates unchanged, runs the full gate on them, and then
merges the pull request here with a merge commit. Your commits stay in this repository's
history under your name. When a change has to be carried over by hand, the commit that
includes it names you in a co-author line.

## Your data

SynapTech s. r. o., Studená 5089/3, 900 42 Dunajská Lužná, company ID (IČO) 57 334 111, is the
controller of the personal data that the contribution process records. You reach it at
hello@straza.ai.

We record your GitHub account name and number, the pull request, your acceptance comment
with its date and time, the version of the agreement you accepted, and our confirmation.
For a contribution we merge, we also keep your commits, which carry the name and email
address you put in them. We keep these records to prove the license you granted us, which
is necessary for the agreement you accept and is our legitimate interest in showing who
licensed what, under Article 6(1)(b) and (f) of the General Data Protection Regulation.

The records are kept in a private repository of the strazahq organization on GitHub and
in the company's own backups. GitHub hosts the repository and may process the records
outside the European Union under its own data protection terms. We keep them for as long as the licensed rights last,
which is the author's life and 70 years after, and delete them then.

You may ask us for access to your data, for its correction or restriction, and for a
copy of your data in a portable format, and you may object to its processing. We may refuse to erase a record while it is needed to prove the
license, because it then serves the establishment and defense of legal claims. You may
also complain to the Office for Personal Data Protection of the Slovak Republic.

Accepting the agreement, and so this record, is a condition of contributing code.

Questions go to hello@straza.ai. A vulnerability goes to the channel in
[SECURITY.md](SECURITY.md), never to a public issue.
