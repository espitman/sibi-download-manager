# Download settings behavior

Settings and the Downloads-screen Preferences sheet use the same persisted
`SettingsRepository`. Saving either surface updates the other surface immediately;
an open Preferences draft also refreshes when the shared value changes elsewhere.

| Setting | Application boundary | Active-download behavior |
| --- | --- | --- |
| Wi-Fi only | Immediate | The network coordinator pauses active transfers on validated non-Wi-Fi networks and may requeue only policy-paused transfers when allowed again. |
| Auto-resume | Immediate | Controls subsequent automatic recovery decisions. It never resumes a manual pause. |
| Download complete | Immediate | Enables or suppresses future completion transitions; enabling it does not replay historical completions. |
| Speed alerts | Immediate | Starts monitoring active transfers from the current moment; disabling it cancels visible stall alerts. |
| Unlimited / global speed limit / Wi-Fi-only limit | Immediate | The shared aggregate limiter changes the allowance of all active and future transfer connections. |
| Simultaneous downloads | Next queue admission | The scheduler uses the new limit immediately for new admissions. Lowering it does not cancel transfers already running. |
| Connections | Next transfer | The segmented engine reads the value when a transfer starts. Existing segment requests keep their original plan. |
| Save location | Future downloads | Existing records keep their captured destination; newly submitted downloads use the new location. |
| Theme | Immediate | All visible app surfaces recompose with the selected theme. |

Reset semantics are specified and tested by SDM-053.
