# Sofia 0.47: historical ready orders

Pickups now combines committed Daily Report work with owner-entered old ready orders. Its mirror adds Source=Old Receipts for the latter. Sofia 0.47 recognizes that source as ready even if no historical worker was recorded. A follow-up then says the worker is not recorded, without inventing a worker or calling the order unfinished. Ordinary Daily Report rows retain their existing sign-off readiness rule.

The release also includes the earlier tested for/four number fix (the local 0.46 candidate was not installed). All 147 unit tests passed and the APK built with the existing shop signing configuration. Published to the same existing private, owner-controlled update folders. No sharing or permission changes.

Final channel verification downloaded all 39 published pieces, checked each hash, and checked the assembled APK size/hash against both latest.json and the exact local build: version 0.47/code47, size 160072472 bytes, SHA-256 `3348af55e43caa8bf45ae3e8c527695f7ac7c8a2f5ad1cb1fddb54ae103d7dda`.

No customer messages, report writes or pickup actions were performed for testing. The owner phone was not USB-connected, so installation and physical-device behavior are not claimed. Sofia Clock is unrelated and unchanged.
