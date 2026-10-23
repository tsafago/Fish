# Compatibility and acceptance

**Status: experimental working client. Full ACS compatibility is not demonstrated.**
There was no IBM i endpoint, test account, ACS installation or real-session
capture available during implementation. Synthetic tests are valuable protocol
evidence but are not real-host acceptance.

## Locally demonstrated

`.\build.ps1 Test` exercises the actual selected engine and live desktop,
not a separate presentation mock. Golden fixtures and exact byte assertions
live in `tests\pane5250\Tests.java`; the live host is `demo\DemoHost`.

| Area | Local evidence |
|---|---|
| Stream framing | Byte-at-a-time negotiation, interleaved options, repeated requests, IAC quoting, GDS length/type/header checks, malformed/truncated input failures |
| TN5250 negotiation | IBM-3477-FC only; bidirectional BINARY/EOR; optional option refusal; TERMINAL-TYPE SEND; NEW-ENVIRON names, workstation and keyboard/code-page values |
| Optional plain sign-on | Exact RFC 4777 USER / empty IBMRSEED / IBMSUBSPW bytes; visible refusal; no credentials negotiated in manual mode |
| Presentation | Actual 24x80 and 27x132 datastreams, host-driven transitions in both directions; pane/window resize does not change dimensions |
| Fields/input | Host-defined fields, protected/numeric validation, Field Exit, literal bracket safety, local-error Reset and preserved host inhibition |
| Host keys | Exact Enter/F1-F24/Clear/Help/roll AIDs; header-only Attention and System Request with EBCDIC option; physical desktop input and mapped keypad Field Plus |
| Read/query/save | Exact RFC 1205 Query shape/capabilities, Read MDT / Alternate embedded-null differences, Read Immediate Alternate AID/cursor, Save/Restore round trip |
| Attributes | Host color/reverse and restored blink flags; hidden-field-safe copy/export; password field/capacity guards |
| Code pages | Bidirectional engine mappings and usable UI encoders for every offered SBCS page |
| Credentials | Real Windows Credential Manager write/read/delete round trip, uniquely named synthetic fixture, Unicode secret |
| Desktop | Three independent real TCP sockets, physical keyboard focus/edit, keyboard pane navigation, socket-preserving center/edge docking, real close/reopen persistence |
| Profiles/layout | Identity-safe duplication, all docking directions, atomic XML persistence, writer locking, explicit corrupt-state handling, reserved shortcut namespace |

SBCS pages offered: **37, 273, 277, 278, 280, 284, 285, 297, 500, 870, 871,
1140, 1141, 1147, 1148**. Mapping round trips do not establish the correctness
of every national keyboard combination against a real host.

## Known implementation gaps

| Gap | Current behavior / consequence |
|---|---|
| Enhanced Write-to-Display structured fields (windows, GUI selection fields, scrollbars, grids) | Stop the connection explicitly. These are not advertised as capabilities. Real applications using them will not work. |
| WEA extended attributes and WSF beyond the basic Query | Explicit failure rather than silent skipping. Additional valid IBM i datastreams may therefore be rejected. |
| Full applicable 5250 order/FCW validation | Mature upstream behavior is inherited where not adapted; exhaustive conformance, transparent field FCWs and all signed/continued/mandatory-field combinations are not independently established. |
| Cryptographically substituted automatic sign-on | DES/SHA substitutions and server-specific password-policy variants are not implemented. Only opt-in RFC 4777 plain sign-on is offered. |
| Extended startup/confirmation records and optional IBM environment extensions | Not requested. Unexpected unsupported record types produce an explicit error, not a guessed success response. |
| DBCS and other terminal models | Unsupported; only IBM-3477-FC and the listed SBCS pages are offered. |
| TLS / Kerberos | Not implemented; TLS is out of scope and Kerberos was optional. |
| Accessibility / DPI matrices | Keyboard routes and Java2D fixed-cell rendering are implemented; screen-reader terminal semantics, mixed-DPI monitors and all Windows scaling/keyboard layouts need hands-on acceptance. |
| Performance | Bounded output, off-UI I/O, no third-party runtime JARs; no startup-latency or memory SLA is claimed. |

Only the basic Query capabilities actually intended for this implementation
are announced: both screen sizes, color, alternate MDT reads and Move Cursor;
no graphics, DBCS, enhanced GUI or row-1/column-1 capability is claimed.
Upstream warnings/unsupported diagnostics surface as connection failures.
This preserves synchronization but is intentionally stricter than ACS for some
valid host variations. It is a compatibility limitation, not completed tolerance.

Host STRPCCMD and browser/external-program execution are disabled. A host cannot
use this client to launch local commands. Password data may exist in the JVM,
helper process and host screen memory; no secure-memory-erasure guarantee is made.

## Required real IBM i / ACS comparison

Use a non-production workstation profile and an authorized test account.
Provide the IBM i hostname/address and plain-Telnet port, IBM i release,
device-name constraints, sign-on policy (manual vs permitted RFC 4777 plain),
needed code pages and representative display-file applications. Arrange access
to an ACS 5250 display session configured with matching settings. Do not put
credentials or unsanitized captures in this repository.

For each comparison record host/application versions, terminal/code page,
screen/cursor/field state, expected outbound input and both clients' behavior.
Use authorized, sanitized packet captures where needed; plain TN5250 captures
can contain passwords and business data.

1. Compare initial negotiation and Query, including reordered/refused optional
   options, device allocation/rejection, ordinary and unsuccessful sign-on.
2. Compare all applicable commands, orders, structured fields and FCWs used by
   real applications. Implement missing enhanced/extended behavior before
   declaring those applications supported.
3. Compare 24x80/27x132 transitions, output-only updates, cursor/IC/MC control,
   field boundaries, mandatory/signed/continued fields, numeric edits and Reset.
4. Compare all F1-F24, Enter, Field Exit/Plus/Minus, Attention, System Request,
   Clear, Help, rolls, message light, cancellation, and input inhibition.
5. Compare all selected code-page glyphs and reverse/underline/blink/non-display
   attributes, paste, selection and copied output.
6. Exercise multiple independent device sessions, disconnect/reconnect and host
   restarts; drag and keyboard-dock them while traffic is active.
7. Reopen profiles/layouts, verify credential opt-in/removal, test supported
   Windows DPI scales and document measured startup/input responsiveness.

Until those gates and the known implementation gaps are resolved, do not label
the client production-ready, fully TN5250-conformant or ACS-compatible.

## Sources and adaptation provenance

- [RFC 1205](https://www.rfc-editor.org/rfc/rfc1205), especially framing,
  operation flows, transparent data, alternate reads and the basic Query.
- [RFC 4777](https://www.rfc-editor.org/rfc/rfc4777), IBM i NEW-ENVIRON and
  optional plain automatic sign-on.
- RFC 854/855/856, 885, 1091 and 1572 for Telnet option semantics.
- [TN5250J](https://github.com/tn5250j/tn5250j/tree/d160492db8adec5d7cf309c6f4feefa162e6565d),
  pinned source, original licenses retained.
- [tn5250 session implementation](https://github.com/tn5250/tn5250/blob/b8448fecd2825bc8e1aa3361371844fb8f25697d/lib5250/session.c),
  cross-check of field-read formatting; not an additional binary dependency.

RFC 1205 supplements IBM publication SA21-9247-6; it is not the entire 5250
specification. That complete IBM reference and actual ACS captures were not
available here. Core adaptations are explicit, fail-on-missing-anchor
transformations in `scripts\prepare-core.ps1`, not untracked submodule edits.
