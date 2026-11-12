# Code conventions

The reference implementation is `platform/SymbolIndex.scala` (`record`, `removeFile`). When a rule below is unclear,
read how those methods do it. Refactors that apply these rules must not change behavior.

## Method shape

Write a method as a recipe, in this order, separating the parts with a blank line (no comment naming the part):

1. Guard clauses, then declarations: values, builders and accumulators the method needs.
2. The operation itself.
3. Publishing, tracking, cleanup and the returned value.

Methods that are a single expression or a `match` (normalizers, dispatch tables) do not need the recipe.

- A callback that captures local context stays an inline anonymous class or lambda. Promote it to a named class only
  when a second caller needs it or it grows logic of its own.
- A helper must give a meaningful name to a transformation, or centralize a rule shared by several callers. Keep
  one-off logic in its caller. A local `def` is preferred over a private member when only one method uses it.
- A chain of calls that does not fit the formatter's line limit breaks into one call per line.

## Data

- A tuple with more than two fields, or one of any size that is returned from or passed between methods, becomes
  a private `case class` next to its owner. A two-field tuple used only inside one expression (a `zip`, a map
  entry, a `for` pattern) may stay.
- Imports: when a pass touches a file's imports, it leaves the whole block sorted within its groups (`com`/`org`,
  then `java`, then `scala`), one blank line between groups.
- Use one word per concept across the code base. In the indexes, a *posting* is one index entry of one file.
- Every `asInstanceOf` has a comment saying why it is sound. Do not add casts to silence the compiler.

## Documentation

- **Public members** (anything not `private`, including `private[pkg]` and `protected`) get a full Scaladoc:
  a summary of the effect, the contract (locks the caller holds, state it must establish first, what the empty
  or absent case does), and `@param` for every parameter and `@return` unless the result is `Unit`.
  `@return` states what the type does not: the meaning of `true`/`false`, ordering, distinctness. It never
  repeats the type.
- **Exception: platform overrides that only reject the call** (their body is `CallLog.unsupported(...)` or
  equivalent) get a one-line `/** ... */` such as `Rejects asynchronous commits; the host commits synchronously.`
  with no `@param` or `@throws`: the platform declares the signature and the body is the whole contract.
- **Private members** get a one-line `/** ... */` that states the effect. Invariants shared by a whole class
  live in the class documentation, not in each method. A precondition specific to one private method goes in
  that method's sentence.
- Local `def`s get the same one-liner when their purpose is not evident from the name.
- Inline `//` comments explain a non-obvious why: an invariant, a platform quirk, a tradeoff. They never narrate
  what the syntax does.

## Out of scope for a standardization pass

Renaming public API, changing locking or concurrency, splitting files, and moving behavior between classes are
separate changes. Report them instead of doing them.
