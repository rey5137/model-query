# RFC 0012 — External model bindings: models compiled without their entity

- **Status:** accepted (2026-10-13; recorded as D-134 and Q-19)
- **Affects:** `processor/30` (new R-PROC-28 to R-PROC-31, `@BindQueryModel`/`@BindUpdateModel`/`@BindInsertModel`);
  `processor/31` (new R-GEN-35, where a bound model's QModel is generated); `processor/32` (new `MQ3023` to `MQ3026`,
  AC-PROC-23 to AC-PROC-26); `reference/90` (the four codes); `reference/92` (D-134, Q-19); `delivery/61`
  (incubating list); `docs/site` (`models.md`, a "models in an API module" section)
- **Discussion:** to open

## Summary

Let a model live in a module that cannot see the JPA entities: the record (or class) carries only its field
annotations, and a **binding** declared in the persistence module names the model and its root entity. The processor
runs in the persistence module, reads the model from the classpath, validates it against the entity exactly as today,
and generates the QModel there. The API module depends on `model-query-annotations` alone (no JPA, no Hibernate, no
entities), so a model can be handed to a controller, a client SDK or another service as a plain record.

```java
// api module: depends on model-query-annotations only
public record OrderView(@PrimaryKey Long id, String number, BigDecimal total,
                        @Column(attribute = "customer.id") Long customerId) {}

// persistence module: sees the entity and the api module
@BindQueryModel(model = OrderView.class, root = OrderEntity.class)
final class OrderModels {}
// generates com.acme.persistence.QOrderView, validated against OrderEntity
```

## Motivation

A model already has no runtime dependency on its entity: every model-query annotation has `CLASS` retention, and the
entity is referenced only by the generated QModel and the query code. The coupling is at **compile time**:
`@QueryModel(root = OrderEntity.class)` puts the entity on the model's compile path, and the processor, which must
see the entity to validate attributes and types (P-2), runs where the model is compiled. So a team that keeps its
API contracts in a module with no persistence dependency today either

- duplicates every model as a DTO and maps by hand or with MapStruct, which is the boilerplate model-query exists to
  remove, or
- lets the API module depend on the entity module, which leaks JPA types and Hibernate onto every consumer's
  classpath.

The processor can already read a model from the classpath rather than the compilation: a nested model (D-45) is read
from class files, its record components and their `CLASS`-retained annotations included. A binding generalises that
from "a nested model" to "the model itself".

## Design

### 1. The binding annotations (R-PROC-28)

Three `@Incubating`, `CLASS`-retained annotations in `model-query-annotations`, each repeatable and allowed on a type
or a `package-info`:

```java
@BindQueryModel(model = OrderView.class, root = OrderEntity.class,
                prefix = "", suffix = "", singleGroup = false, generateChanges = false, generateInserts = false,
                expressions = {})
@BindUpdateModel(model = OrderPatch.class, root = OrderEntity.class)
@BindInsertModel(model = OrderCreate.class, root = OrderEntity.class)
```

Each mirrors the elements of `@QueryModel`, `@UpdateModel` and `@InsertModel` one for one, plus `model`. Nothing
about the model's fields moves: `@PrimaryKey`, `@Column`, `@Join`, `@Child`, `@Aggregate`, `@GroupBy`, `@Selected`,
`@Transient`, `@FilterColumn` and the `@ExcludeFrom*` annotations stay on the model and mean what they mean today.
A bound model must not also carry `@QueryModel`, `@UpdateModel` or `@InsertModel`, and is bound at most once per
kind: `MQ3023`.

### 2. Expressions the API module cannot compile (R-PROC-29)

`@Computed(RowNet.class)` and `@Aggregate(expression = …)` name an `ExpressionDefinition`, which builds columns over
the entity and so cannot compile in the API module. On a bound model `@Computed` may be written without a value
(`void.class` becomes the declared default of `@Computed.value`), and an `@Aggregate` leaves `expression` at its
existing default; the binding supplies the definition:

```java
@BindQueryModel(model = OrderNet.class, root = OrderEntity.class,
                expressions = @BindExpression(field = "net", value = RowNet.class))
```

A `@BindExpression` on an `@Aggregate` field turns it into an expression aggregate over that definition, as
`expression = …` does on an annotated model. A valueless `@Computed` with no matching `@BindExpression`, a
`@BindExpression` naming an unknown field, a field that is neither `@Computed` nor `@Aggregate`, or one whose
annotation already names a definition, or a valueless `@Computed` on an annotated (unbound) model: `MQ3025`.
Converters (`@Column(converter = …)`) stay on the model: they convert the model type to the attribute type and do not
need the entity; one that does is the user's choice to keep in the API module or not.

### 3. Where the QModel goes (R-GEN-35)

A bound model's QModel is generated in the **binding's package**, named by the binding's `prefix`/`suffix` (D-44 rules
unchanged) and the model's simple name: `com.acme.persistence.QOrderView`. Generating into the model's package would
split that package across two modules, which JPMS refuses and which is confusing on the classpath. Because the
QModel is in another package, the model type, its canonical constructor (record) or no-arg constructor and setters
(class), and its accessors must be public: `MQ3024` otherwise.

Nested models (`@Join`, `@Child`) of a bound model are found as today (D-45) when they are annotated, and also when
they are bound in the **same compilation**; the outer QModel reads the nested QModel from the binding's package. A
nested model that is neither: `MQ3026`. Binding a nested model in another persistence module is Q-19.

### 4. What does not change

- **Runtime:** none. `core`, `jpa` and `spring-data` never read model annotations; they work through the QModel,
  which is the same class with the same constants whether the model was annotated or bound. INV-7 holds.
- **Validation:** every check in `processor/32` runs on a bound model with the same codes, against the bound root. A
  bound model gets exactly the compile-time guarantees of an annotated one (P-2).
- **Spring:** a repository typed by a bound model and its QModel works unchanged.

### New ids

| Id | Where | What |
|---|---|---|
| R-PROC-28 | `processor/30` | The three binding annotations, one binding per model and kind |
| R-PROC-29 | `processor/30` | A valueless `@Computed`, and `@BindExpression` for computed and aggregate fields |
| R-PROC-30 | `processor/30` | A bound model is read from the classpath; field annotations are read from its class file |
| R-PROC-31 | `processor/30` | Nested models of a bound model: annotated, or bound in the same compilation |
| R-GEN-35 | `processor/31` | A bound model's QModel is generated in the binding's package; public-access rule |
| MQ3023 | `processor/32` | A model bound twice for one kind, or both bound and annotated |
| MQ3024 | `processor/32` | A bound model, its constructor or its accessors are not public |
| MQ3025 | `processor/32` | A valueless expression with no binding, or a `@BindExpression` that matches nothing |
| MQ3026 | `processor/32` | A nested model of a bound model that is neither annotated nor bound in the compilation |
| AC-PROC-23..26 | `processor/32` | A compile-testing case per code, plus a positive case per binding kind |
| AC-GEN-30 | `processor/31` | A bound model's QModel equals the annotated model's QModel apart from its package (golden file) |

**D-134**: models may be bound from the persistence module (§1–§3). **Q-19**: nested models bound
in another module.

## Compatibility

Additive and `@Incubating`. The only change to an existing annotation is the declared default `void.class` on
`@Computed.value` (`@Aggregate.expression` already defaults to it), which is source and binary compatible; an annotated model that leaves
it unset is `MQ3025`, the same failure it gets today from a missing required element, with a clearer message.

## Alternatives

- **A string root (`@QueryModel(rootName = "com.acme.OrderEntity")`).** The processor in the API module still
  cannot see the entity, so it either drops every attribute and type check (against P-2) or fails.
- **No annotations in the API module at all; the binding names every field.** Keeps the API module free of even
  `model-query-annotations`, but restates each field as a string, which the record already says, and loses the
  compile-time link between field and mapping. The annotations jar has no dependencies, so the cost of the current
  design is small.
- **Generate a DTO from the model (or the model from a DTO).** Two types for one shape, the duplication this RFC
  removes.
- **The QModel in the model's package.** Split packages (§3).
- **Do nothing.** A model is already entity-free at runtime; teams that only need to pass a model outward within one
  application can keep it next to its entity. Not enough for an API module or a published client.

## Unresolved questions

- **Spike (M24.1):** confirm on the supported JDKs that javac exposes a classpath record's component annotations
  (`CLASS` retention, `RECORD_COMPONENT` and `FIELD` targets) and a classpath class's field annotations through the
  `Element` API with their values, and that Gradle and Maven incremental builds recompile the persistence module when
  the bound model changes (the processor declares itself aggregating for bindings). *Resolved by D-140: the
  annotations read the same from the classpath on JDK 17 and 21; the processor stays isolating, and a Gradle build
  misses an edit that only changes annotations on the model's private members unless `compileJava` declares the
  runtime classpath as an input.*
- **Q-19:** binding a nested model in a different persistence module from its outer model.
- **Release (decided 2026-10-13):** M24, after M23, shipped in 0.9.0 with M17 to M23.
