# Security policy

The Straza approver app is where a person approves or denies what an AI agent asked to
do. A vulnerability in it can let an agent act on an approval that the person never
gave, so reports take priority over feature work.

## Reporting a vulnerability

Do not open a public issue for a security bug.

Report it privately through [GitHub private vulnerability reporting](https://github.com/strazahq/straza-mobile/security/advisories/new)
on this repository, or by email to security@straza.ai. Include what you can: the
affected component (the Android app in its `foss` or `play` flavor, the iOS app, the
shared Kotlin code in `shared/`, or the development server `mock-strazad`), the app
version or commit, the phone model and its operating system version, the steps that
reproduce it and your view of the impact. A proof of concept is welcome. Test only
against systems you own.

The enrollment QR payload and the approver API are wire formats that the
[server repository](https://github.com/strazahq/straza) owns. Report an issue in either
format, or in how the app and the server exchange them, once, to the server repository
through its [private vulnerability reporting](https://github.com/strazahq/straza/security/advisories/new)
or by email to security@straza.ai, and not a second time here.

You get an acknowledgment within 72 hours and a triage verdict (accepted, duplicate or
not a vulnerability, with the reasoning) within 7 days. An accepted report gets a fix
or a documented mitigation with a target of 90 days, ordered by severity. The highest
class is a decision signature that is forged or replayed, the hardware key leaving the
phone or signing without the operating system's authentication prompt, a bypass of the
TLS pin, a push payload that the app trusts without fetching the request from the
paired server, request content that reaches the lock screen or, on Android, a
screenshot, and an enrollment to a server that the person did not scan. Disclosure is
coordinated: we agree a publication date with you, credit you in the advisory unless
you decline, and publish a GitHub Security Advisory that names the fixed versions.

## Supported versions

The latest release of the app in each store, and the release before it, receive
security fixes. A fix ships as a new release of the app, so updating the app is how you
get it.

## Scope

README.md describes the app's security model under "Why you can trust the answer". The
guarantees it names define the scope: the signing key is generated in the phone's
secure hardware and never leaves it, every signature needs the operating system's
authentication prompt, a signed decision is valid once, the app talks to no server but
the ones it is paired with apart from the push service, it trusts only the pinned key
when the enrollment QR carries a pin, a push is never trusted without the fetch from
the paired server, and nothing about a request reaches the lock screen or, in an
Android release build, a screenshot. A violation of any of them is in scope and serious
by definition.
