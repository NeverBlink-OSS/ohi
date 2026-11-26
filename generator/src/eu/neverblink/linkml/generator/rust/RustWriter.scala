package eu.neverblink.linkml.generator.rust

import eu.neverblink.linkml.generator.options.RustGeneratorOptions
import eu.neverblink.linkml.generator.util.GeneratedHeader
import eu.neverblink.linkml.schemaview.SchemaView

/** Writes the files of a [[Crate]]. */
private[rust] final class RustWriter(crate: Crate, options: RustGeneratorOptions)(using
    SchemaView,
) {
  import RustWriter.*

  private val header = GeneratedHeader("//")

  /** The files of the crate, by path. */
  def crateFiles: Seq[(String, String)] = {
    val src = if options.handwrittenLib then "src/generated" else "src"
    val main = if options.handwrittenLib then "mod.rs" else "lib.rs"
    Seq(
      "Cargo.toml" -> cargoToml,
      "pyproject.toml" -> pyprojectToml,
      s"$src/$main" -> lib(singleFile = false),
      s"$src/serde_utils.rs" -> (header + RustSupport.serdeUtils),
      s"$src/poly.rs" -> poly,
      s"$src/poly_containers.rs" -> (header + RustSupport.polyContainers),
    ) ++ Option.when(options.stubgen)(s"$src/stub_utils.rs" -> (header + RustSupport.stubUtils)) ++
      Option.when(options.stubgen)("src/bin/stub_gen.rs" -> stubGen) ++
      Option.when(options.handwrittenLib)("src/lib.rs" -> libShim)
  }

  /** The whole crate as one file, with the serde helpers inline and without the traits. */
  def singleFile: String = lib(singleFile = true)

  private def lib(singleFile: Boolean): String = {
    val out = StringBuilder(header)
    out ++= "\n#![allow(non_camel_case_types)]\n\n"
    if singleFile then
      out ++= s"#[cfg(feature = \"serde\")]\nmod serde_utils {\n${RustSupport.serdeUtils}}\n"
    else {
      out ++= "#[cfg(feature = \"serde\")]\nmod serde_utils;\npub mod poly;\npub mod poly_containers;\n"
      if options.stubgen then out ++= "#[cfg(feature = \"stubgen\")]\npub mod stub_utils;\n"
    }
    out ++= "\n"
    crate.imports.foreach(i => out ++= useLine(i))
    out ++= "use std::collections::BTreeMap;\n\n// Types\n\n"
    crate.types.foreach((_, t) => out ++= typeAlias(t))
    if crate.usesLocalizedText then out ++= "\n" ++= RustSupport.localizedText
    out ++= "\n// Slots\n\n"
    crate.slots.foreach((_, s) => out ++= typeAlias(s))
    out ++= "\n// Enums\n\n"
    crate.enums.foreach {
      case (_, e: RustEnum) => out ++= rustEnum(e) ++= "\n"
      case (_, t: TypeAlias) => out ++= typeAlias(t) ++= "\n"
    }
    crate.subpropertyEnums.foreach((_, e) => out ++= rustEnum(e) ++= "\n")
    out ++= "// Classes\n\n"
    crate.classes.foreach((_, c) => out ++= rustClass(c) ++= "\n")
    val structs = crate.classes.collect { case (_, s: RustClass.Struct) => s }
    if structs.exists(_.merge) then out ++= RustSupport.overwriteExceptNone
    if structs.exists(_.properties.exists(p => p.merge && mergeStrategy(p) == optionMapMerge)) then
      out ++= RustSupport.optionMapOverwrite
    if options.stubgen then
      out ++= "\n#[cfg(feature = \"stubgen\")]\ndefine_stub_info_gatherer!(stub_info);\n"
    if options.pyo3 then out ++= pyModule(structs)
    out.result()
  }

  private def useLine(i: RustImport): String = {
    val module = i.module.replace('-', '_')
    val path = i.objects match {
      case Seq() => module + i.alias.fold("")(a => s" as $a")
      case Seq(one) => s"$module::$one"
      case many => s"$module::{${many.mkString(", ")}}"
    }
    featureGate(i.feature) + s"use $path;\n"
  }

  private def typeAlias(t: TypeAlias): String = s"pub type ${t.name} = ${t.target};\n"

  private def pyModule(structs: Seq[RustClass.Struct]): String = {
    val signature =
      if options.handwrittenLib then
        "pub fn register_pymodule(m: &Bound<'_, PyModule>) -> PyResult<()> {"
      else
        s"#[pymodule]\n#[pyo3(name = \"${crate.name}\")]\n" +
          s"fn ${crate.name}(m: &Bound<'_, PyModule>) -> PyResult<()> {"
    val classes = structs.map(s => s"    m.add_class::<${s.name}>()?;\n").mkString
    s"\n#[cfg(feature = \"pyo3\")]\n$signature\n$classes    Ok(())\n}\n"
  }

  // Enums

  private def rustEnum(e: RustEnum): String = {
    val name = e.name
    val variants = e.values.map((v, text) =>
      s"    #[cfg_attr(feature = \"serde\", serde(rename = ${rustString(text)}))]\n    $v,\n",
    ).mkString
    val display =
      e.values.map((v, text) => s"            $name::$v => f.write_str(${rustString(text)}),\n")
        .mkString
    val toPython =
      e.values.map((v, text) => s"            $name::$v => ${rustString(text)},\n").mkString
    val fromPython = e.values.map { (v, text) =>
      val pattern = Seq(text, v).distinct.map(rustString).mkString(" | ")
      s"                $pattern => Ok($name::$v),\n"
    }.mkString
    val literals = e.values.map((_, text) => text.replace("'", "\\'"))
    val stubType = rustString(literals.mkString("typing.Literal['", "', '", "']"))
    s"""#[derive(Debug, Clone, PartialEq)]
       |#[cfg_attr(feature = "serde", derive(Serialize, Deserialize))]
       |pub enum $name {
       |$variants}
       |
       |impl core::fmt::Display for $name {
       |    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
       |        match self {
       |$display        }
       |    }
       |}
       |
       |#[cfg(feature = "pyo3")]
       |impl<'py> IntoPyObject<'py> for $name {
       |    type Target = PyAny;
       |    type Output = Bound<'py, PyAny>;
       |    type Error = PyErr;
       |    fn into_pyobject(self, py: Python<'py>) -> Result<Bound<'py, PyAny>, PyErr> {
       |        let s: &str = match self {
       |$toPython        };
       |        Ok(pyo3::types::PyString::new(py, s).into_any())
       |    }
       |}
       |
       |#[cfg(feature = "pyo3")]
       |impl<'a, 'py> FromPyObject<'a, 'py> for $name {
       |    type Error = PyErr;
       |    fn extract(ob: pyo3::Borrowed<'a, 'py, pyo3::types::PyAny>) -> pyo3::PyResult<Self> {
       |        if let Ok(s) = ob.extract::<&str>() {
       |            match s {
       |${fromPython}                _ => Err(PyErr::new::<pyo3::exceptions::PyValueError, _>(
       |                    format!("invalid value for $name: {}", s),
       |                )),
       |            }
       |        } else {
       |            Err(PyErr::new::<pyo3::exceptions::PyTypeError, _>(
       |                concat!("expected str for ", stringify!($name)),
       |            ))
       |        }
       |    }
       |}
       |
       |#[cfg(feature = "stubgen")]
       |impl ::pyo3_stub_gen::PyStubType for $name {
       |    fn type_output() -> ::pyo3_stub_gen::TypeInfo {
       |        ::pyo3_stub_gen::TypeInfo::with_module($stubType, "typing".into())
       |    }
       |}
       |
       |#[cfg(feature = "stubgen")]
       |impl ::pyo3_stub_gen::runtime::PyRuntimeType for $name {
       |    fn runtime_type_object(py: pyo3::Python<'_>) -> pyo3::PyResult<pyo3::Bound<'_, pyo3::PyAny>> {
       |        // Enums are exposed to Python as plain strings.
       |        Ok(py.get_type::<pyo3::types::PyString>().into_any())
       |    }
       |}
       |""".stripMargin
  }

  // Classes

  private def rustClass(c: RustClass): String = c match {
    case RustClass.Any(name) => RustSupport.anything(name)
    case RustClass.AnyAlias(name, target) => s"pub type $name = $target;\n"
    case s: RustClass.Struct =>
      unionModule(s) + struct(s) + s.keyValue.fold("")(keyValue(s.name, _)) +
        s.subtypes.fold("")(subtypeEnum)
  }

  private def unionModule(s: RustClass.Struct): String =
    if s.unions.isEmpty then ""
    else
      s"pub mod ${s.unionModule} {\n    use super::*;\n\n${s.unions.map(slotUnion).mkString("\n")}}\n\n"

  private def slotUnion(u: SlotUnion): String = {
    val name = u.name
    val variants = u.ranges.map((v, t) => s"    $v($t),\n").mkString
    val extract = u.ranges.map { (v, t) =>
      s"""        if let Ok(val) = ob.extract::<$t>() {
         |            return Ok($name::$v(val));
         |        }
         |""".stripMargin
    }.mkString
    val toPython = u.ranges.map { (v, _) =>
      s"            $name::$v(val) => Ok(val.into_pyobject(py).map(move |b| " +
        "<pyo3::Bound<'_, _> as Clone>::clone(&b).into_any())?),\n"
    }.mkString
    indent(s"""#[derive(Debug, Clone, PartialEq)]
       |#[cfg_attr(feature = "serde", derive(Serialize, Deserialize))]
       |#[cfg_attr(feature = "serde", serde(untagged))]
       |pub enum $name {
       |$variants}
       |
       |#[cfg(feature = "pyo3")]
       |impl<'a, 'py> FromPyObject<'a, 'py> for $name {
       |    type Error = PyErr;
       |    fn extract(ob: pyo3::Borrowed<'a, 'py, pyo3::types::PyAny>) -> pyo3::PyResult<Self> {
       |$extract        Err(PyErr::new::<pyo3::exceptions::PyTypeError, _>(
       |            "invalid ${u.slotName}",
       |        ))
       |    }
       |}
       |
       |#[cfg(feature = "pyo3")]
       |impl<'py> IntoPyObject<'py> for $name {
       |    type Target = PyAny;
       |    type Output = Bound<'py, PyAny>;
       |    type Error = PyErr;
       |
       |    fn into_pyobject(self, py: Python<'py>) -> Result<Bound<'py, PyAny>, PyErr> {
       |        match self {
       |$toPython        }
       |    }
       |}
       |${boxConversions(name, s"invalid ${u.slotName}")}
       |#[cfg(feature = "stubgen")]
       |::pyo3_stub_gen::impl_stub_type!($name = ${u.ranges.map(_._2).mkString(" | ")});
       |""".stripMargin)
  }

  private def struct(s: RustClass.Struct): String = {
    val name = s.name
    val out = StringBuilder()
    out ++= "#[derive(Debug, Clone, PartialEq)]\n"
    out ++= "#[cfg_attr(feature = \"serde\", derive(Serialize, Deserialize))]\n"
    if options.stubgen then out ++= "#[cfg_attr(feature = \"stubgen\", gen_stub_pyclass)]\n"
    out ++= "#[cfg_attr(feature = \"pyo3\", pyclass(subclass, get_all, set_all, from_py_object))]\n"
    if s.merge then out ++= "#[derive(Merge)]\n"
    out ++= s"pub struct $name {\n"
    out ++= s.properties.map(p => indent(property(p))).mkString(",\n")
    out ++= (if s.properties.isEmpty then "}\n" else "\n}\n")
    if s.properties.nonEmpty then {
      // The required parameters come first, as Python needs the ones with defaults last
      val params = s.properties.filterNot(_.range.optional) ++ s.properties.filter(_.range.optional)
      val signature =
        params.map(p => if p.range.optional then s"${p.name}=None" else p.name).mkString(", ")
      val arguments = params.map(p => s"${p.name}: ${p.range.constructorType}").mkString(", ")
      val conversions = params.flatMap(p =>
        p.range.constructorConversion(p.name).map(c => s"        let ${p.name} = $c;\n"),
      ).mkString
      val stubMethods =
        if options.stubgen then "#[cfg_attr(feature = \"stubgen\", gen_stub_pymethods)]\n" else ""
      out ++= s"""
         |#[cfg(feature = "pyo3")]
         |$stubMethods#[pymethods]
         |impl $name {
         |    #[new]
         |    #[pyo3(signature = ($signature))]
         |    pub fn new($arguments) -> Self {
         |$conversions        $name { ${params.map(_.name).mkString(", ")} }
         |    }
         |}
         |
         |${boxConversions(name, s"invalid $name")}         |""".stripMargin
      if s.merge then out ++= s"""
         |impl $name {
         |    pub fn merge_with(&mut self, other: &$name) {
         |        self.merge(other.clone())
         |    }
         |}
         |""".stripMargin
    }
    out.result()
  }

  private def property(p: RustProperty): String = {
    val out = StringBuilder()
    if p.merge then out ++= s"#[merge(${mergeStrategy(p)})]\n"
    val optional = if p.range.optional then "_optional" else ""
    def serdeWith(helper: String) =
      s"""#[cfg_attr(feature = "serde", serde(
         |    deserialize_with = "serde_utils::deserialize_$helper$optional",
         |    serialize_with = "serde_utils::serialize_$helper$optional"
         |))]
         |""".stripMargin
    (p.isKeyValue, p.container, p.inlining) match {
      // For `inlined_as_list`: reading accepts a list or a dict, and writing writes a list
      case (true, Some(Container.List), SlotInline.Inline) =>
        out ++= serdeAttribute(
          s"deserialize_with = \"serde_utils::deserialize_inlined_dict_list$optional\"",
        )
      case (true, Some(Container.Mapping), SlotInline.Inline) =>
        out ++= serdeWith("inlined_dict_map")
      case (false, Some(Container.List), SlotInline.Primitive) =>
        out ++= serdeWith("primitive_list_or_single_value")
      case _ =>
    }
    if p.multivalued || !p.required then out ++= serdeAttribute("default")
    p.rename.foreach(r => out ++= serdeAttribute(s"rename = ${rustString(r)}"))
    p.alias.foreach(a => out ++= serdeAttribute(s"alias = ${rustString(a)}"))
    val skip =
      if p.range.optional then Some("Option::is_none")
      else
        p.range.container.map {
          case Container.List => "Vec::is_empty"
          case Container.Mapping => "HashMap::is_empty"
        }
    skip.foreach(f => out ++= serdeAttribute(s"skip_serializing_if = \"$f\""))
    out ++= s"pub ${p.name}: ${p.range.fieldType}"
    out.result()
  }

  private def serdeAttribute(attribute: String): String =
    s"#[cfg_attr(feature = \"serde\", serde($attribute))]\n"

  private def keyValue(name: String, kv: KeyValue): String = {
    def insertKey = s"""        let key_value = serde_value::to_value(k.clone())
       |            .map_err(|e| format!("unable to serialize key: {}", e))?;
       |        map.insert(Value::String(${rustString(kv.keySerdeName)}.into()), key_value);
       |""".stripMargin
    val deserialize = """        let de = Value::Map(map).into_deserializer();
       |        match serde_path_to_error::deserialize(de) {
       |            Ok(ok) => Ok(ok),
       |            Err(e) => Err(format!("at `{}`: {}", e.path(), e.inner())),
       |        }
       |""".stripMargin
    val keyed = (kv.keySerdeName +: kv.keyAliases)
      .map(k => s"map.contains_key(&Value::String(${rustString(k)}.into()))").mkString(" || ")
    val fromSimple = kv.simpleValue match {
      case Some(value) =>
        s"""    fn from_pair_simple(k: Self::Key, v: Value) -> Result<Self, Self::Error> {
           |        let mut map: BTreeMap<Value, Value> = BTreeMap::new();
           |$insertKey        map.insert(Value::String(${rustString(value.serdeName)}.into()), v);
           |$deserialize    }
           |
           |    fn simple_value(&self) -> Option<&Self::Value> {
           |        ${
            if value.optional then s"self.${value.name}.as_ref()" else s"Some(&self.${value.name})"
          }
           |    }
           |""".stripMargin
      case None if kv.fromEmpty =>
        s"""    fn from_pair_simple(k: Self::Key, _v: Value) -> Result<Self, Self::Error> {
           |        let mut map: BTreeMap<Value, Value> = BTreeMap::new();
           |$insertKey$deserialize    }
           |""".stripMargin
      case None =>
        s"""    fn from_pair_simple(_k: Self::Key, _v: Value) -> Result<Self, Self::Error> {
           |        Err("Cannot create a $name from a primitive value!".into())
           |    }
           |""".stripMargin
    }
    s"""
       |#[cfg(feature = "serde")]
       |impl serde_utils::InlinedPair for $name {
       |    type Key = ${kv.keyType};
       |    type Value = ${kv.simpleValue.fold("Value")(_.tpe)};
       |    type Error = String;
       |
       |    fn extract_key(&self) -> &Self::Key {
       |        &self.${kv.keyName}
       |    }
       |
       |    fn from_pair_mapping(k: Self::Key, v: Value) -> Result<Self, Self::Error> {
       |        let mut map = match v {
       |            Value::Map(m) => m,
       |            _ => return Err("ClassDefinition must be a mapping".into()),
       |        };
       |        let key_value = serde_value::to_value(k.clone())
       |            .map_err(|e| format!("unable to serialize key: {}", e))?;
       |        // Insert the key, unless the object has it already, under its name or its alias
       |        let already_keyed = $keyed;
       |        if !already_keyed {
       |            map.insert(Value::String(${rustString(kv.keySerdeName)}.into()), key_value);
       |        }
       |$deserialize    }
       |
       |$fromSimple
       |    fn compact_value(&self) -> Option<Value> {
       |        let value = match serde_value::to_value(self) {
       |            Ok(v) => v,
       |            Err(_) => return None,
       |        };
       |        match value {
       |            Value::Map(mut map) => {
       |                map.remove(&Value::String(${rustString(kv.keySerdeName)}.into()));
       |                Some(Value::Map(map))
       |            }
       |            _ => None,
       |        }
       |    }
       |}
       |""".stripMargin
  }

  private def subtypeEnum(e: SubtypeEnum): String = {
    val name = e.name
    val out = StringBuilder("\n#[derive(Debug, Clone, PartialEq)]\n")
    if e.designator.isEmpty then
      out ++= "#[cfg_attr(feature = \"serde\", derive(Serialize, Deserialize))]\n" ++=
        "#[cfg_attr(feature = \"serde\", serde(untagged))]\n"
    out ++= s"pub enum $name {\n${e.variants.map(v => s"    $v($v),\n").mkString}}\n"
    e.designator.foreach(designator => out ++= designatorSerde(e, designator))
    out ++= "\n"
    e.variants.foreach(v =>
      out ++= s"impl From<$v> for $name {\n    fn from(x: $v) -> Self {\n        Self::$v(x)\n    }\n}\n",
    )
    val extract = e.variants.map { v =>
      s"""        if let Ok(val) = ob.extract::<$v>() {
         |            return Ok($name::$v(val));
         |        }
         |""".stripMargin
    }.mkString
    val toPython = e.variants
      .map(v =>
        s"            $name::$v(val) => val.into_pyobject(py).map(move |b| b.into_any()),\n",
      )
      .mkString
    out ++= s"""
       |#[cfg(feature = "pyo3")]
       |impl<'a, 'py> FromPyObject<'a, 'py> for $name {
       |    type Error = PyErr;
       |    fn extract(ob: pyo3::Borrowed<'a, 'py, pyo3::types::PyAny>) -> pyo3::PyResult<Self> {
       |$extract        Err(PyErr::new::<pyo3::exceptions::PyTypeError, _>(
       |            "invalid $name",
       |        ))
       |    }
       |}
       |
       |#[cfg(feature = "pyo3")]
       |impl<'py> IntoPyObject<'py> for $name {
       |    type Target = PyAny;
       |    type Output = Bound<'py, PyAny>;
       |    type Error = PyErr;
       |
       |    fn into_pyobject(self, py: Python<'py>) -> Result<Bound<'py, PyAny>, PyErr> {
       |        match self {
       |$toPython        }
       |    }
       |}
       |${boxConversions(name, s"invalid $name")}""".stripMargin
    e.keyType.foreach { keyType =>
      def tryVariants(method: String) = e.variants.map { v =>
        s"""        if let Ok(x) = $v::$method(k.clone(), v.clone()) {
           |            return Ok($name::$v(x));
           |        }
           |""".stripMargin
      }.mkString
      val fromMapping = tryVariants("from_pair_mapping")
      val fromSimple = tryVariants("from_pair_simple")
      val keys =
        e.variants.map(v => s"            $name::$v(inner) => inner.extract_key(),\n").mkString
      out ++= s"""
         |#[cfg(feature = "serde")]
         |impl serde_utils::InlinedPair for $name {
         |    type Key = $keyType;
         |    type Value = serde_value::Value;
         |    type Error = String;
         |
         |    fn from_pair_mapping(k: $keyType, v: serde_value::Value) -> Result<Self, String> {
         |$fromMapping        Err("none of the variants matched the mapping form".into())
         |    }
         |
         |    fn from_pair_simple(k: $keyType, v: serde_value::Value) -> Result<Self, String> {
         |$fromSimple        Err("none of the variants support the primitive form".into())
         |    }
         |
         |    fn extract_key(&self) -> &$keyType {
         |        match self {
         |$keys        }
         |    }
         |}
         |""".stripMargin
    }
    out ++= s"\n#[cfg(feature = \"stubgen\")]\n::pyo3_stub_gen::impl_stub_type!($name = ${e.variants.mkString(" | ")});\n"
    out.result()
  }

  /** `Serialize` and `Deserialize` of an `OrSubtype` enum with a type designator. The designator is
    * a field of each struct, so the struct writes and reads it. A derived `serde(tag = ...)` would
    * write it twice, and remove it from the struct when reading.
    */
  private def designatorSerde(e: SubtypeEnum, designator: String): String = {
    val name = e.name
    val expected =
      e.variants.flatMap(e.designatorValues).map(v => s"            ${rustString(v)},\n").mkString
    val arms = e.variants.map { v =>
      val values = e.designatorValues(v).map(rustString).mkString(" | ")
      s"""            $values => $v::deserialize(
         |                serde_value::ValueDeserializer::<D::Error>::new(value),
         |            )
         |            .map($name::$v),
         |""".stripMargin
    }.mkString
    val serialize =
      e.variants.map(v =>
        s"            $name::$v(inner) => inner.serialize(serializer),\n",
      ).mkString
    s"""
       |#[cfg(feature = "serde")]
       |impl Serialize for $name {
       |    fn serialize<S: serde::Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
       |        match self {
       |$serialize        }
       |    }
       |}
       |
       |#[cfg(feature = "serde")]
       |impl<'de> Deserialize<'de> for $name {
       |    fn deserialize<D: serde::Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
       |        use serde::de::Error as _;
       |
       |        const EXPECTED: &[&str] = &[
       |$expected        ];
       |
       |        // Read the whole value first, so that the struct of the variant can read the
       |        // designator too
       |        let value = Value::deserialize(deserializer)?;
       |        let tag = match &value {
       |            Value::Map(map) => map
       |                .get(&Value::String(${rustString(designator)}.to_string()))
       |                .and_then(|v| match v {
       |                    Value::String(s) => Some(s.clone()),
       |                    _ => None,
       |                }),
       |            _ => None,
       |        };
       |        let tag = tag.ok_or_else(|| D::Error::missing_field(${rustString(designator)}))?;
       |
       |        match tag.as_str() {
       |$arms            other => Err(D::Error::unknown_variant(other, EXPECTED)),
       |        }
       |    }
       |}
       |""".stripMargin
  }

  private def boxConversions(name: String, error: String): String =
    s"""
       |#[cfg(feature = "pyo3")]
       |impl<'py> IntoPyObject<'py> for Box<$name> {
       |    type Target = PyAny;
       |    type Output = Bound<'py, Self::Target>;
       |    type Error = PyErr;
       |    fn into_pyobject(self, py: Python<'py>) -> Result<Self::Output, Self::Error> {
       |        (*self).into_pyobject(py).map(move |x| x.into_any())
       |    }
       |}
       |
       |#[cfg(feature = "pyo3")]
       |impl<'a, 'py> FromPyObject<'a, 'py> for Box<$name> {
       |    type Error = PyErr;
       |    fn extract(ob: pyo3::Borrowed<'a, 'py, pyo3::types::PyAny>) -> pyo3::PyResult<Self> {
       |        if let Ok(val) = ob.extract::<$name>() {
       |            return Ok(Box::new(val));
       |        }
       |        Err(PyErr::new::<pyo3::exceptions::PyTypeError, _>(
       |            "$error",
       |        ))
       |    }
       |}
       |""".stripMargin

  // Traits

  private def poly: String = {
    val out = StringBuilder(header)
    out ++= "\n#![allow(non_camel_case_types)]\n\nuse crate::*;\nuse crate::poly_containers::*;\n"
    crate.traits.foreach { t =>
      val supertraits =
        if t.supertraits.isEmpty then "" else t.supertraits.mkString(": ", " + ", "")
      out ++= s"\npub trait ${t.name}$supertraits {\n"
      t.getters.foreach((name, getter) => out ++= s"    ${getterSignature(name, getter)};\n")
      out ++= "}\n"
      t.impls.foreach { impl =>
        out ++= s"\nimpl ${t.name} for crate::${impl.struct} {\n"
        out ++= impl.getters.map(getter.tupled).mkString("\n")
        out ++= "}\n"
      }
      t.subtypeImpls.foreach { impl =>
        out ++= s"\nimpl ${t.name} for crate::${impl.subtypeEnum} {\n"
        out ++= impl.getters.map((name, promoted) => subtypeGetter(t.name, name, promoted, impl))
          .mkString("\n")
        out ++= "}\n"
      }
    }
    out.result()
  }

  private def getterSignature(name: String, returnType: String): String =
    if RustRange.needsLifetime(returnType) then s"fn $name<'a>(&'a self) -> $returnType"
    else s"fn $name(&self) -> $returnType"

  /** A getter of an `OrSubtype` enum, which calls the getter of the struct in the variant. The call
    * names the trait, as the struct can have a getter of the same name from another trait, through
    * another mixin.
    */
  private def subtypeGetter(
      traitName: String,
      name: String,
      promoted: RustRange,
      impl: PolySubtypeImpl,
  ): String = {
    // A `Vec` or `HashMap` view becomes a `ListAny` or `MapAny`. A union is returned by value.
    val convert = (promoted.container.isDefined && !promoted.isUnion, promoted.optional) match {
      case (false, _) => ""
      case (true, true) => ".map(|x| x.to_any())"
      case (true, false) => ".to_any()"
    }
    val arms = impl.variants
      .map(v => s"            ${impl.subtypeEnum}::$v(val) => $traitName::$name(val)$convert,\n")
      .mkString
    s"    ${getterSignature(name, promoted.getterType)} {\n        match self {\n$arms        }\n    }\n"
  }

  /** A getter in the trait implementation of a struct. It returns the field, a view of the field,
    * or the field converted into the return type of the trait getter, if that is a union.
    */
  private def getter(name: String, range: RustRange, promoted: RustRange): String = {
    val body =
      if promoted.isUnion then unionGetterBody(name, range, promoted)
      else plainGetterBody(name, range, promoted)
    s"    ${getterSignature(name, promoted.getterType)} {\n$body    }\n"
  }

  private def unionGetterBody(name: String, range: RustRange, promoted: RustRange): String = {
    val union = promoted.tpe
    val sameUnion = range.isUnion && range.tpe == union
    val field = s"self.$name"
    // Converts a value of the field into the trait's union: puts it into the variant of its type,
    // or, if the field is a union itself, moves its content into the variant of the same name
    def convert(value: String, reference: String): String =
      if !range.isUnion then s"$union::${range.typeName}($value.clone())"
      else if sameUnion then s"$value.clone()"
      else {
        val arms = range.members.map { m =>
          s"                ${range.tpe}::${m.typeName}(x) => $union::${m.typeName}(x.clone()),\n"
        }.mkString
        s"match $reference {\n$arms            }"
      }
    // The conversion of an item `v` of a `Vec` or `HashMap`
    val item = if range.boxNeeded then convert("(**v)", "&**v") else convert("v", "v")
    val copied = sameUnion && !range.boxNeeded
    val expression = range.container match {
      case Some(Container.List) =>
        def all(xs: String) =
          if copied then s"$xs.clone()" else s"$xs.iter().map(|v| $item).collect()"
        if range.optional then s"$field.as_ref().map(|xs| ${all("xs")})" else all(field)
      case Some(Container.Mapping) =>
        def all(m: String) =
          if copied then s"$m.clone()" else s"$m.iter().map(|(k, v)| (k.clone(), $item)).collect()"
        if range.optional then s"$field.as_ref().map(|m| ${all("m")})" else all(field)
      case None =>
        val (value, reference, optional) =
          if range.boxNeeded then (s"(*$field)", s"&*$field", s"$field.as_deref()")
          else (field, s"&$field", s"$field.as_ref()")
        if range.optional then
          if sameUnion then s"$optional.cloned()" else s"$optional.map(|v| ${convert("v", "v")})"
        else if promoted.optional then s"Some(${convert(value, reference)})"
        else convert(value, reference)
    }
    s"        $expression\n"
  }

  private def plainGetterBody(name: String, range: RustRange, promoted: RustRange): String = {
    val getterType = promoted.getterType
    val field = s"self.$name"
    val expression =
      if range.boxNeeded then
        range.container match {
          case Some(Container.List) =>
            if range.optional then s"$field.as_ref().map(|x| poly_containers::ListView::new(x))"
            else s"poly_containers::ListView::new(&$field)"
          case Some(Container.Mapping) =>
            if range.optional then s"$field.as_ref().map(poly_containers::MapView::new)"
            else s"poly_containers::MapView::new(&$field)"
          case None =>
            if range.optional then s"$field.as_deref()"
            else if promoted.optional then s"Some(&*$field)"
            else s"&*$field"
        }
      else if range.optional then
        if getterType == "Option<&str>" || getterType == "Option<&'a str>" then s"$field.as_deref()"
        else if getterType == "Option<String>" then s"$field.as_ref().map(|x| x.to_string())"
        else if range.container.isEmpty && range.isCopy then field
        else s"$field.as_ref()"
      else if promoted.optional then
        if getterType == "Option<String>" then s"Some($field.to_string())"
        else if range.isCopy then s"Some($field)"
        else if getterType == "Option<&'a str>" then s"Some(&$field[..])"
        else s"Some(&$field)"
      else if range.container.isDefined then s"&$field"
      else if range.isCopy then field
      else if getterType == "&'a str" || getterType == "&str" then s"&$field[..]"
      else s"&$field"
    s"        return $expression;\n"
  }

  // Other files

  private def cargoToml: String = {
    val dependencies = crate.imports.filter(_.version.isDefined).distinctBy(_.crate).map { i =>
      val version = s"version = \"${i.version.get}\""
      val features =
        if i.features.isEmpty then ""
        else i.features.map(f => s"\"$f\"").mkString(", features = [", ", ", "]")
      val optional = if i.feature.isDefined then ", optional = true" else ""
      if features.isEmpty && optional.isEmpty then s"${i.crate} = \"${i.version.get}\"\n"
      else s"${i.crate} = { $version$features$optional }\n"
    }.mkString
    val defaults = Seq("pyo3" -> options.pyo3, "serde" -> options.serde).collect { case (f, true) =>
      s"\"$f\""
    }
    val features = crate.imports.flatMap(i => i.feature.map(_ -> i)).groupMap(_._1)(_._2).toSeq
      .sortBy((f, _) => crate.imports.indexWhere(_.feature.contains(f)))
      .map { (feature, imports) =>
        val enabled = imports.map(i => s"dep:${i.crate}") ++ imports.flatMap(_.featureDependencies)
        s"$feature = [${enabled.distinct.map(e => s"\"$e\"").mkString(", ")}]\n"
      }.mkString
    val crateType = if options.pyo3 then "[\"cdylib\", \"rlib\"]" else "[\"lib\"]"
    s"""${GeneratedHeader("#")}
       |[package]
       |name = "${crate.name}"
       |version = "${crate.version}"
       |edition = "2021"
       |
       |[lib]
       |name = "${crate.name}"
       |crate-type = $crateType
       |
       |[dependencies]
       |$dependencies
       |[features]
       |default = [${defaults.mkString(", ")}]
       |$features""".stripMargin
  }

  private def pyprojectToml: String =
    s"""${GeneratedHeader("#")}
       |[project]
       |name = "${crate.name}"
       |version = "${crate.version}"
       |
       |[build-system]
       |requires = ["maturin>=1.0,<2.0"]
       |build-backend = "maturin"
       |
       |[tool.maturin]
       |features = ["pyo3/extension-module"]
       |""".stripMargin

  private def stubGen: String = header + RustSupport.stubGen(crate.name)

  /** The `lib.rs` of a crate with `handwritten_lib`, which the user may change. */
  private def libShim: String = {
    val chrono = crate.imports.find(_.crate == "chrono").fold("")(
      _.objects.map(o => s"pub use chrono::$o;\n").mkString,
    )
    val loader = crate.rootStruct.fold("") { case RootStruct(root, function) =>
      s"""
         |#[cfg(feature = "serde")]
         |/// Loads a YAML document into the root class. Edit or extend as needed.
         |pub fn $function<P>(
         |    path: P,
         |) -> Result<generated::$root, Box<dyn std::error::Error + Send + Sync>>
         |where
         |    P: AsRef<std::path::Path>,
         |{
         |    let file = std::fs::File::open(path)?;
         |    let reader = std::io::BufReader::new(file);
         |    let parsed = yaml_serde::from_reader(reader)?;
         |    Ok(parsed)
         |}
         |
         |#[cfg(all(feature = "pyo3", feature = "serde"))]
         |#[pyfunction(name = "$function")]
         |fn ${function}_py(path: &str) -> PyResult<generated::$root> {
         |    $function(path)
         |        .map_err(|err| PyErr::new::<pyo3::exceptions::PyIOError, _>(err.to_string()))
         |}
         |""".stripMargin
    }
    val module =
      if !options.pyo3 then ""
      else {
        val addLoader = crate.rootStruct.fold("") { root =>
          s"""    #[cfg(all(feature = "serde", feature = "pyo3"))]
             |    {
             |        m.add_function(wrap_pyfunction!(${root.loader}_py, m)?)?;
             |    }
             |""".stripMargin
        }
        s"""
           |#[cfg(feature = "pyo3")]
           |use pyo3::prelude::*;
           |#[cfg(all(feature = "pyo3", feature = "serde"))]
           |use pyo3::wrap_pyfunction;
           |
           |#[cfg(feature = "pyo3")]
           |#[pymodule]
           |#[pyo3(name = "${crate.name}")]
           |fn ${crate.name}(m: &Bound<'_, PyModule>) -> PyResult<()> {
           |    generated::register_pymodule(m)?;
           |$addLoader    Ok(())
           |}
           |""".stripMargin
      }
    s"""//! This file is generated only once, so you can change it. The other generated files are
       |//! replaced each time. To generate it again, delete it and run the generator.
       |
       |pub mod generated;
       |
       |pub use generated::*;
       |$chrono$loader$module""".stripMargin
  }
}

private object RustWriter {
  private val optionMapMerge = "strategy = option_map_overwrite"

  private def mergeStrategy(p: RustProperty): String =
    (p.range.container, p.range.optional) match {
      case (Some(Container.Mapping), true) => optionMapMerge
      case (Some(Container.Mapping), false) => "strategy = merge::hashmap::overwrite"
      case (_, true) => "strategy = overwrite_except_none"
      case _ => "skip"
    }

  private def featureGate(feature: Option[String]): String =
    feature.fold("")(f => s"#[cfg(feature = \"$f\")]\n")

  /** [[text]] indented by four spaces, except empty lines. */
  private def indent(text: String): String =
    text.linesWithSeparators.map(l => if l.isBlank then l else "    " + l).mkString

  /** A Rust string literal of [[text]]. */
  def rustString(text: String): String = {
    val escaped = text.flatMap {
      case '\\' => "\\\\"
      case '"' => "\\\""
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case c => c.toString
    }
    s"\"$escaped\""
  }
}
