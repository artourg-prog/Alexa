# Sofia 0.48: touch entry and receipt lookup

The owner approved the emerald/violet phone prototype and requested publication in the working Sofia app. A bundled, network-blocked WebView now presents the approved screens through the existing private native services. The launcher opens Sofia / Hello with Daily Report on the left and Find on the right; holding Sofia opens update and support settings. No microphone is opened by the new activity. The existing standalone Sofia Clock is unchanged.

Find uses four phone-ending digits and the existing read-only pickup mirror. Multiple matches require a choice. Viewing and Not picked up do not write or remove an order. Picked up records the existing durable request, retains its id across retries, and returns to the home screen with a truthful local acceptance message; pending tablet confirmation remains visible. Unknown payment is never described as paid.

Daily Report uses receipt entry, the exact configured approved worker list, an explicit SMS/SAVE choice and the final number/date/worker confirmation. Work and SMS use the existing Finishes durable event and endpoints. Re-entering the same worker/receipt/day reuses the event. Recorded appears only after ADDED or ALREADY; Customer SMS sent additionally requires SENT. Intermediate or failed states retain actionable status and allow Start for the next receipt. There is no spoken queue announcement. Receipts absent from the ready-order mirror are shown as entered/unverified and validated by the Reader when saved; customer/payment/amount fields are not invented.

The same package, signer, private configuration files and queue formats are retained. Existing MainActivity code remains available internally for compatibility; normal launcher/wake targets the new touch activity.

Validation: 147 JVM tests passed; three Android emulator tests passed on synthetic transports, covering both pickup choices, save versus SMS, duplicate event reuse, server-confirmed final status and not falsely treating ACCEPTED/QUEUED as Recorded/Sent. The local font and approved appearance were inspected in emulator images. No real SMS, report save or pickup mutation was used for testing. The shop phone was not USB-connected; installation on the physical Samsung is not claimed.

Publication outcome is recorded separately in audit/touch-release/publication.json after every uploaded piece and the final channel manifest have been verified. No GitHub push is part of this publication.
