# SDM implementation rules

## Design fidelity is mandatory

- The Open Design prototype at `/Users/espitman/Library/Application Support/Open Design/namespaces/release-stable/data/projects/13ab3c0f-1414-409f-934f-981d4a24c89f/index.html` is the single visual and interaction source of truth.
- Every implemented screen, component, sheet, dialog, control, state, animation, string, icon, font, color, radius, size, spacing, safe-area inset, and interaction must match that reference pixel for pixel.
- Do not substitute generic Android or Material UI when the reference contains a custom design.
- Do not add behavior or visual elements that are absent from the reference unless the user explicitly requests them.
- Do not simplify or approximate a referenced interaction. Reproduce its exact structure and visible states.
- A change with any avoidable visual or interaction difference from the reference is rejected and must not be presented as complete.
- After implementation, capture the actual connected-device UI at the same logical viewport and compare it against the rendered reference before reporting completion.
- The user-requested removals remain authoritative: do not restore `Always keep active`, automatic media detection/downloading, or YouTube-specific access.

