# Model and tool preferences

- Use Astra only for heavy, important, exceptional tasks that warrant it. Do not select Astra for routine work.
- Prefer Sol and the `agy` CLI much more frequently for everyday work, when available and suitable for the task.
- Apply this preference when choosing models or execution tools. Do not claim to have switched the active conversation's model unless it has actually changed.

# Task reporting

- Do not record model names or token usage under completed tasks or in task reports. The user has revoked the previous accounting requirement.

--- project-doc ---

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

## graphify

This project alone has a local code graph at `graphify-out/graph.json`. Do not merge it into Graphify's global graph or activate Graphify for other projects.

- For questions about this project's structure or dependencies, start with `graphify query "<question>"`. Use `graphify path "<A>" "<B>"` or `graphify explain "<concept>"` for focused relationships.
- Verify important findings against the original source files before treating them as facts. The graph may be incomplete or stale, especially after code changes.
- After modifying this project's code, run `graphify update .` to refresh the local graph. Do not run a global update.
