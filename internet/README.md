# Fisker Warm Internet Gateway · TEST ONLY

Two standalone Android apps and a private HTTPS relay for authenticated test delivery.
No PKC/CAN actuation is implemented. `TEST_ACCEPTED` never means HVAC is running.

[Installation, device choice, security, power and remaining PKC work (Ukrainian)](../docs/INTERNET-GATEWAY-STAGE6-UA.md).

Tests: `python3 scripts/test_internet.py` (JDK17, Node24). Builds use the existing owner's pinned signing key in GitHub Actions; no secrets belong in this directory.
