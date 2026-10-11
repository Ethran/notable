# Contributing to Notable

Thanks for wanting to help! Notable is maintained mostly by one person in my spare time. 
The rules below exist to keep reviews possible. PRs
that ignore them may be closed with a link to this file --  that's not personal, you're welcome to
reopen once they're addressed.

## Before you start

- **Bugs:** open an issue using the bug report template
- **Features and larger changes:** open an issue or ask on [Discord](https://discord.gg/rvNHgaDmN2)
  first. It's much cheaper to agree on an approach before the code exists.
- Read [docs/file-structure.md](docs/file-structure.md) to see where code belongs, and
  [docs/database-structure.md](docs/database-structure.md) if you touch the data model. Those files are only meant as a helpers, 
they might have some mistakes.


## Pull request size

Small PRs get reviewed. Big PRs wait.

"Changed lines" below means additions + deletions, **not counting** tests (`src/test`,
`src/androidTest`), Room schema snapshots (`app/schemas/`), and docs.

| Size                  | Changed lines | Rule                                                                         |
|-----------------------|---------------|------------------------------------------------------------------------------|
| Small                 | up to ~300    | Anyone can open one.                                                         |
| Large                 | ~300 – 1000   | Only after **you've had at least one small PR merged** here.                 |
| Very large            | over 1000     | Only after agreeing on it in an issue first. Expect to be asked to split it. |

**Your first PR must be a small one.** 

### One PR = one concern

- Don't mix a feature with a refactor, a rename, a formatting pass, or unrelated fixes.
- Preparatory refactors go in their own PR, merged before the feature that needs them.
- If a feature is big, split it into a sequence of PRs that each build, pass tests, and make sense
  on their own (e.g. data model → logic → UI). Say in each description where it fits in the chain.
- Don't reformat files you aren't otherwise changing.

## The PR description must be written by you

**Write the PR description yourself, in your own words.** 
It is the one place where I learn what *you* understood, decided, and tested.

- You can use a tool to translate your text or to fix grammar and spelling.
- If you want to include AI output (a generated summary, change list, explanation, or code it
  proposed), put it in the collapsible **AI-generated content** section of the PR template. It's
  optional, and it's read as supporting material only.

It's fine for the description to be short. "Fixes #123: eraser left ghosts after a GC refresh.
Tested on Note Air 3, scribbled and erased ~20 times." is a ok description.

## Using AI to write code

AI-assisted code is accepted, with conditions:

- **You are the author.** You must have read and understood every line you submit, and be able to
  answer review questions about it without asking the AI.
- **Say how you used AI** in the PR template (none / assisted / mostly generated).
- Don't submit code you haven't run. "Builds" is not "tested".
- Don't use AI to answer review comments on your behalf.

## Automated review

PRs are reviewed by [CodeRabbit](https://www.coderabbit.ai/).