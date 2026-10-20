# Proposed request: owner-authorized local climate integration

Status: prepared for review; **not sent**. No personal or vehicle-identifying data attached.

Proposed To: **info@undercurrentauto.com** — OLP publisher support, verified from Google Play.

Proposed Cc: **info@fiskeroa.com** — FOA membership/licensing contact, verified from current FOA pages.

Subject: **PKC 21.3 — supported owner provisioning or local climate-only integration for a personal Android app**

Dear Undercurrent and FOA technical teams,

I own a 2023 EU Fisker Ocean in Ukraine with Ocean OS 2.1 and PKC 21.3. I have not used OceanLink Pro on this vehicle and do not have a previously issued BLE/VA credential. Please treat this as first owner provisioning, rather than recovery of an expired OLP login or old cache.

My personal Android project, Fisker Warm, needs one function: start the vehicle's native climate session using its last settings, with fresh execution feedback and the native stop/timeout. I am not seeking access to unlock, drive, arbitrary diagnostics or individual HVAC actuators.

I understand that the Chevalier Diagnostic Tool does not offer individual key export and that Phone SDK access is licensed. I am therefore asking about a **licensee-supported integration**, including an option that keeps the raw vehicle secret inside an authorized component.

Could the responsible technical/licensing contact please confirm:

1. On the already installed PKC21.3, does “no BLE key required” mean automatic retrieval of an existing secret, or is there a documented owner-authorized phone enrollment mode without a raw VA key? If the latter exists, what owner proof and supported procedure does it require?
2. Can you offer either a permitted one-time owner credential export, a personal SDK/specification licence, or a supported **local** Android API that Fisker Warm can call? Please distinguish these from access limited to the OLP app and its private backend.
3. Can provisioning be completed for this existing firmware in Ukraine without firmware updates, ECU reset, or changes to current FOB/NFC credentials? What is the supported way to verify the owner/VIN and resolve any previous-owner record?
4. What are the complete initial and recurring charges? Is paid FOA membership needed only for provisioning or for continued operation, and what expires or is revoked when membership ends?
5. After provisioning, can the supported climate operation run locally without your server per connection or command? Please specify any credential renewal, app-signing/package restrictions, offline limits, and dependency on a particular SDK.
6. Is start-only with a 15-minute native session supported on this exact firmware? What confirms actual HVAC operation, preservation of the last settings, explicit stop and vehicle-side timeout when the phone disconnects?

If independent integration is unavailable, please state this explicitly and identify any supported alternative; please do not interpret app membership alone as permission or an interface for a separate client.

This is an initial capability enquiry. VIN, ownership documents, account credentials and payment information are intentionally omitted. If needed later, please describe your secure verification channel and the exact operations for separate owner approval. No vehicle operations are requested at this stage.

Kind regards,

A Fisker Ocean owner / Fisker Warm project
