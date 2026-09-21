---
name: skill-installer
description: Install, list, and uninstall user-level skills
when_to_use: The user asks to install, add, list, show, remove, or uninstall a skill
version: "1.0.0"
---

# Skill Installer

This skill tells you how to manage user-level skills. Skills are stored as
directories under the user skills root path, which is provided in the system
prompt as "User skills are installed under: <path>".

The skills root path is NOT inside the current workspace. Use `run_command` to
create directories, copy files, and run git commands against it. All `run_command`
calls go through the existing approval mode.

## Installing a skill

When the user asks to install a skill:

1. Identify the source:
   - A local directory path on disk, or
   - A git repository URL (optionally with a subdirectory path).
2. If the source is a git URL, first `git clone --depth 1 <url>` to a temporary
   directory, then locate the subdirectory that contains `SKILL.md`.
3. Read the `SKILL.md` frontmatter `name` field to determine the target directory
   name under the skills root.
4. Copy the entire skill directory to `<skills-root>/<name>/`:
   - Windows: use `xcopy /E /I <source> <skills-root>\<name>` or `robocopy`.
   - macOS/Linux: use `cp -R <source> <skills-root>/<name>`.
5. If the source was a git clone, clean up the temporary directory afterward.
6. Verify that `<skills-root>/<name>/SKILL.md` exists and has a valid frontmatter.
7. Tell the user the skill is installed. Note that newly installed skills only
   appear in the installed skills list after a new session starts — the list is
   a startup snapshot, so the current session will not see it yet.

## Listing installed skills

List the immediate subdirectories of the skills root with `run_command`, then
read each `SKILL.md` frontmatter `name` and `description` to summarize what is
installed.

## Uninstalling a skill

Delete the entire `<skills-root>/<name>/` directory with `run_command`. Confirm
with the user before deleting if the request is ambiguous.
