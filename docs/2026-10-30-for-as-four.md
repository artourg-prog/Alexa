# Prefer “for” as four in number dictation

Version 0.46 fixes leading/trailing “for” being stripped from dictated numbers. An isolated “for” can also contribute 4 when a lookup is awaiting a number across recognition segments. “Last for digits” is treated as a number label. The visible transcript and intent parser use the same context.

Grammatical phrases such as “ready for pickup”, “find order for six eight two four”, and “receipt 12345 for Alex” retain their meaning. This is number interpretation after speech recognition, not an acoustic model change.

Validation: all 145 unit tests passed, including seven new regression tests; debug APK built successfully. No customer searches, SMS, pickups or report writes were executed. USB device inventory was empty, so this build has not been installed on the owner phone or published to its update feed.
