# Comprehensive Interaction Test Plan

## DemoUI

| # | Element | Action | Expected JFX | Expected Web |
|---|---------|--------|-------------|-------------|
| 1 | Counter + | click | counter-value = "1" | counter-value = "1" |
| 2 | Counter + | click again | counter-value = "2" | counter-value = "2" |
| 3 | Counter - | click | counter-value = "1" | counter-value = "1" |
| 4 | Todo input | fill "Buy milk" | filled | filled |
| 5 | Todo Add | click | "Buy milk" in list | "Buy milk" in list |
| 6 | Todo input | fill "Walk dog" | filled | filled |
| 7 | Todo Add | click | 2 items in list | 2 items |
| 8 | Todo delete first | click x on first | only "Walk dog" | only "Walk dog" |
| 9 | Theme toggle | click | dark mode on | dark class present |
| 10 | Theme toggle | click again | dark mode off | dark class gone |

## FormUI

| # | Element | Action | Expected JFX | Expected Web |
|---|---------|--------|-------------|-------------|
| 11 | Name input | fill "Alice" | filled | filled |
| 12 | Email input | fill "a@b.com" | filled | filled |
| 13 | Textarea | fill "Hello" | filled | filled |
| 14 | Select | change to option2 | selected | selected |
| 15 | Checkbox | click | checked | checked |
| 16 | Submit button | click | "Submitted: Name=Alice..." | same |
| 17 | Disable All button | click | inputs disabled | inputs disabled |
| 18 | Enable All button | click | inputs enabled | inputs enabled |

## ReactiveUI

| # | Element | Action | Expected JFX | Expected Web |
|---|---------|--------|-------------|-------------|
| 19 | Hide Panel btn | click | panel disappears, btn="Show Panel" | same |
| 20 | Show Panel btn | click | panel appears, btn="Hide Panel" | same |
| 21 | Hide visibility btn | click | div hidden, btn="Show" | same |
| 22 | Show visibility btn | click | div visible, btn="Hide" | same |
| 23 | Style B btn | click | text="Current class: style-b" | same |
| 24 | Style C btn | click | text="Current class: style-c" | same |
| 25 | Style A btn | click | text="Current class: style-a" | same |
| 26 | Foreach input | fill "Mango" | filled | filled |
| 27 | Foreach Add | click | "Mango" tag appears | same |
| 28 | View mode toggle | click | grid view (Alpha/Beta/Gamma boxes) | same |
| 29 | View mode toggle | click again | list view (ul with li) | same |

## CollectionOpsUI

| # | Element | Action | Expected JFX | Expected Web |
|---|---------|--------|-------------|-------------|
| 30 | Input | fill "NewTask" | filled | filled |
| 31 | Add button | click | NewTask in keyed list | same |
| 32 | Remove Last | click | NewTask removed | same |
| 33 | Reverse | click | Deploy v1 first, Setup CI last | same |
| 34 | Clear | click | empty state shown | same |
| 35 | Add after clear | fill "Reborn" + Add | "Reborn" appears | same |
| 36 | Tick | click | Count: 1, tags show "(1)" | same |
| 37 | Set Single | click | only "Only" tag | same |
| 38 | Reset | click | Red, Green, Blue tags | same |

## InteractiveUI

| # | Element | Action | Expected JFX | Expected Web |
|---|---------|--------|-------------|-------------|
| 39 | Disable btn | click | btn="Enable", target disabled | same |
| 40 | Enable btn | click | btn="Disable", target enabled | same |
| 41 | Keyboard input | fill "abc" | lastKey shows key events | same |
| 42 | Focus input | click focus input | focus status = "Focused" | same |

## DynamicStyleUI

| # | Element | Action | Expected JFX | Expected Web |
|---|---------|--------|-------------|-------------|
| 43 | Green bg btn | click | bg changes to #dcfce7 | same |
| 44 | Red bg btn | click | bg changes to #fee2e2 | same |
| 45 | A+ font size | click | size becomes 16px | same |
| 46 | A- font size | click | size becomes 14px | same |
| 47 | More padding | click | padding increases | same |
| 48 | Less padding | click | padding decreases | same |
| 49 | Bold ON | click | font-weight: bold applied | same |
| 50 | Italic ON | click | font-style: italic applied | same |
| 51 | Underline ON | click | text-decoration: underline | same |
| 52 | Verify all 3 together | check style attr | all 3 present | all 3 present |
| 53 | Thicker border | click | border increases | same |
| 54 | Thinner border | click | border decreases | same |

## NestedReactiveUI

| # | Element | Action | Expected JFX | Expected Web |
|---|---------|--------|-------------|-------------|
| 55 | Hide Outer | click | outer panel gone | same |
| 56 | Show Outer | click | outer panel back, inner visible | same |
| 57 | Hide Inner | click | inner panel gone, outer stays | same |
| 58 | Show Inner | click | inner panel back | same |
| 59 | Increment All | click | global count = 1 in all items | same |
| 60 | Click Alpha item | click | "Selected: Alpha" | same |
| 61 | Click Beta item | click | "Selected: Beta", Alpha deselected | same |
| 62 | Switch to Tags | click | tags view | same |
| 63 | Switch to List | click | list view | same |
| 64 | Filter (A only) | click | only Alpha shown | same |
| 65 | Show All | click | all items shown | same |

## KeyboardNavUI

| # | Element | Action | Expected JFX | Expected Web |
|---|---------|--------|-------------|-------------|
| 66 | Type input | fill "x" | lastKeyDown/Up shows "x" | same |
| 67 | Key log input | fill "ab" | log shows key events | same |
| 68 | Clear log btn | click | log cleared | same |

## AutoTransitionUI (wait 3s after render)

| # | Element | Action | Expected JFX | Expected Web |
|---|---------|--------|-------------|-------------|
| 69 | Color cycling | check after 3s | phase changes visible | same |
| 70 | Auto list | check after 2s | Alpha, Beta, Gamma items | same |
| 71 | Delayed panel | check after 2s | "Panel appeared!" visible | same |
| 72 | Live counter | check | counter > 0 | same |

## AnimatedDashboardUI (wait 3s after render)

| # | Element | Action | Expected JFX | Expected Web |
|---|---------|--------|-------------|-------------|
| 73 | Users metric | check after 3s | shows "1284" | same |
| 74 | Revenue metric | check after 3s | shows "$48.2K" | same |
| 75 | Status badge | check after 3s | "Active" badge | same |
| 76 | Event log | check after 2s | 3 log entries | same |
| 77 | View toggle | check after 2s | table view | same |

## Static UIs (render + verify no crash)

| # | UI | Action | Expected |
|---|-----|--------|---------|
| 78 | layout | render | OK on both |
| 79 | typography | render | OK on both |
| 80 | semantic | render | OK on both |
| 81 | pseudo | render | OK on both |
| 82 | transforms | render | OK on both |
| 83 | colors | render | OK on both |
| 84 | tables | render | OK on both |
| 85 | dashboard | render | OK on both |
| 86 | sizing | render | OK on both |

**Total: 86 tests**
