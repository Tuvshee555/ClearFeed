# Optional device-level hardening

ClearFeed's Instagram, YouTube and Facebook restrictions are structurally non-disableable **inside ClearFeed**. There is no in-app unrestricted mode, temporary unlock, feed-limit setting, or provider-app handoff.

An ordinary Android application cannot stop its owner from uninstalling it, installing official social applications, opening the same sites in Chrome, changing system settings, using another device, or modifying the OS. ClearFeed does not request Accessibility Service, notification-listener, device-admin, or device-owner privileges and does not claim phone-wide enforcement.

Optional measures outside this app, from lighter to stronger, include:

- uninstalling or disabling the official Instagram, YouTube and Facebook applications;
- disabling supported links for those apps and choosing a filtered browser/site-blocking setup;
- using Android Digital Wellbeing/app timers as an additional voluntary boundary;
- using restricted/supervised profiles or Family Link where appropriate to the owner and account type;
- applying DNS/router filtering with care for collateral breakage of login, messaging and video delivery domains;
- for a dedicated device only, provisioning a transparent Android Enterprise device-owner solution with explicit administration, recovery and offboarding.

These measures have real tradeoffs. Owner-controlled timers and browser rules remain changeable. DNS blocking can break authentication and message media. Supervision affects privacy and account access. Device-owner provisioning may require a factory reset, has powerful management access, and can complicate emergency use and device recovery.

Future managed-device work should be a separate, explicit deployment project with a documented administrator, threat model, consent, recovery key, uninstall/offboarding procedure, and test devices. It must not be hidden inside ClearFeed or represented as necessary for the app's three permanent internal modes.
