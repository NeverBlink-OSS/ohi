package eu.neverblink.linkml.generator.rust

/** The code that is the same in every generated crate, from LinkML-Py's Rust generator. */
private[rust] object RustSupport {

  /** `serde_utils.rs`: reading and writing a class inlined as a list or a dict, and a multivalued
    * slot whose data is a single value. A dict entry is written in the short form `key: value` only
    * if the object has no other fields set.
    */
  val serdeUtils: String =
    """#[cfg(feature = "serde")]
      |use serde::{
      |    de::{DeserializeOwned, IntoDeserializer},
      |    Deserialize,
      |    Deserializer,
      |    Serialize,
      |    Serializer,
      |};
      |#[cfg(feature = "serde")]
      |use serde::de::Error;
      |#[cfg(feature = "serde")]
      |use serde_value::{to_value, Value, ValueDeserializer};
      |#[cfg(feature = "serde")]
      |use std::collections::{BTreeMap, HashMap};
      |#[cfg(all(feature = "serde", feature = "pyo3"))]
      |use pyo3::prelude::*;
      |#[cfg(all(feature = "serde", feature = "pyo3"))]
      |use pyo3::types::{PyAny, PyDict, PyList, PyTuple};
      |
      |#[cfg(feature = "serde")]
      |pub trait InlinedPair: Sized {
      |    type Key: std::hash::Hash + Eq + serde::de::DeserializeOwned + Clone + Ord;
      |    type Value: serde::de::DeserializeOwned;
      |    type Error: std::fmt::Display;
      |
      |    fn from_pair_mapping(k: Self::Key, v: Value) -> Result<Self,Self::Error>;
      |    fn from_pair_simple(k: Self::Key, v: Value) -> Result<Self,Self::Error>;
      |    fn extract_key(&self) -> &Self::Key;
      |
      |    fn simple_value(&self) -> Option<&Self::Value> {
      |        None
      |    }
      |
      |    fn compact_value(&self) -> Option<Value> {
      |        None
      |    }
      |}
      |
      |#[cfg(feature = "serde")]
      |impl<T> InlinedPair for Box<T>
      |where
      |    T: InlinedPair + ?Sized,           // allow unsized boxes too
      |{
      |    type Key   = T::Key;
      |    type Value = T::Value;
      |    type Error = T::Error;
      |
      |    #[inline]
      |    fn from_pair_mapping(k: Self::Key, v: Value) -> Result<Self, Self::Error> {
      |        T::from_pair_mapping(k, v).map(|x| Box::new(x))
      |    }
      |
      |    #[inline]
      |    fn from_pair_simple(k: Self::Key, v: Value) -> Result<Self, Self::Error>  {
      |        T::from_pair_simple(k, v).map(|x| Box::new(x))
      |    }
      |
      |    #[inline]
      |    fn extract_key(&self) -> &Self::Key {
      |        T::extract_key(self)
      |     }
      |
      |    fn simple_value(&self) -> Option<&Self::Value> {
      |        self.as_ref().simple_value()
      |    }
      |
      |    fn compact_value(&self) -> Option<Value> {
      |        self.as_ref().compact_value()
      |    }
      |
      |}
      |
      |#[cfg(feature = "serde")]
      |#[allow(dead_code)]
      |pub fn deserialize_inlined_dict_list<'de, D, T>(de: D) -> Result<Vec<T>, D::Error>
      |where
      |    D: Deserializer<'de>,
      |    T: InlinedPair + Deserialize<'de>,
      |{
      |    let ast: Value = Value::deserialize(de)?;
      |    match ast {
      |        Value::Map(m) => m.into_iter().map(|(k, v)| {
      |            let key: T::Key = Deserialize::deserialize(ValueDeserializer::<D::Error>::new(k))?;
      |            let obj = match v {
      |                Value::Map(_) => T::from_pair_mapping(key, v),
      |                other => T::from_pair_simple(key, other),
      |            };
      |            obj.map_err(D::Error::custom)
      |        }).collect(),
      |        Value::Seq(seq) => seq.into_iter()
      |            .map(|v| T::deserialize(ValueDeserializer::<D::Error>::new(v)))
      |            .collect(),
      |        _ => Err(D::Error::custom("expected mapping or sequence")),
      |    }
      |}
      |
      |#[cfg(feature = "serde")]
      |pub fn deserialize_inlined_dict_map<'de, D, T>(
      |    de: D,
      |) -> Result<HashMap<T::Key, T>, D::Error>
      |where
      |    D: Deserializer<'de>,
      |    T: InlinedPair + Deserialize<'de>,
      |{
      |
      |    // Parse into a generic AST once
      |    let ast: Value = Value::deserialize(de)?;
      |
      |    match ast {
      |        // ---------- { key : value } form ----------
      |        Value::Map(m) => {
      |            let mut out = HashMap::with_capacity(m.len());
      |            for (k_ast, v_ast) in m {
      |                // 1) convert key and value separately
      |                let key: T::Key = Deserialize::deserialize(
      |                    ValueDeserializer::<D::Error>::new(k_ast)
      |                ).map_err(D::Error::custom)?;
      |
      |
      |                // ----------------- decide by the *value* shape
      |                let obj = match v_ast {
      |                    // (1) full object (mapping) -> deserialize directly
      |                    Value::Map(_) => {
      |                        let m: Value = Deserialize::deserialize(
      |                            ValueDeserializer::<D::Error>::new(v_ast)
      |                        ).map_err(D::Error::custom)?;
      |                        T::from_pair_mapping(key.clone(), m).map_err(D::Error::custom)?
      |                    }
      |                    other => {
      |                        T::from_pair_simple(key.clone(), other).map_err(D::Error::custom)?
      |                    }
      |                };
      |
      |
      |                out.insert(key, obj);
      |            }
      |            Ok(out)
      |        }
      |
      |        // ---------- [ value, ... ] form -------------
      |        Value::Seq(seq) => {
      |            let mut out = HashMap::with_capacity(seq.len());
      |            for v_ast in seq {
      |                let val: T = Deserialize::deserialize(
      |                    ValueDeserializer::<D::Error>::new(v_ast)
      |                ).map_err(D::Error::custom)?;
      |
      |                let key = val.extract_key().clone();
      |                if out.insert(key, val).is_some() {
      |                    return Err(D::Error::custom("duplicate key"));
      |                }
      |            }
      |            Ok(out)
      |        }
      |
      |        _ => Err(D::Error::custom("expected mapping or sequence")),
      |    }
      |}
      |
      |#[cfg(feature = "serde")]
      |pub fn deserialize_inlined_dict_map_optional<'de, D, T>(
      |    de: D,
      |) -> Result<Option<HashMap<T::Key, T>>, D::Error>
      |where
      |    D: Deserializer<'de>,
      |    T: InlinedPair + Deserialize<'de>,
      |{
      |    let ast: Value = Value::deserialize(de)?;
      |    match ast {
      |        Value::Unit => Ok(None),
      |        Value::Map(_) | Value::Seq(_) => {
      |            let map = deserialize_inlined_dict_map(ValueDeserializer::<D::Error>::new(ast))?;
      |            Ok(Some(map))
      |        }
      |        _ => Err(D::Error::custom("expected mapping, sequence, or unit")),
      |    }
      |}
      |
      |
      |#[cfg(feature = "serde")]
      |#[allow(dead_code)]
      |pub fn deserialize_inlined_dict_list_optional<'de, D, T>(
      |    de: D,
      |) -> Result<Option<Vec<T>>, D::Error>
      |where
      |    D: Deserializer<'de>,
      |    T: InlinedPair + Deserialize<'de>,
      |{
      |    let ast: Value = Value::deserialize(de)?;
      |    match ast {
      |        Value::Unit => Ok(None),
      |        Value::Map(_) => {
      |            let list = deserialize_inlined_dict_list(ValueDeserializer::<D::Error>::new(ast))?;
      |            Ok(Some(list))
      |        }
      |        Value::Seq(seq) => {
      |            let mut out = Vec::with_capacity(seq.len());
      |            for v_ast in seq {
      |                let val: T = Deserialize::deserialize(ValueDeserializer::<D::Error>::new(v_ast))
      |                    .map_err(D::Error::custom)?;
      |                out.push(val);
      |            }
      |            Ok(Some(out))
      |        }
      |        _ => Err(D::Error::custom("expected mapping, sequence, or unit")),
      |    }
      |}
      |
      |pub fn deserialize_primitive_list_or_single_value<'de, D, T>(
      |    deserializer: D
      |) -> Result<Vec<T>, D::Error>
      |where
      |    D: Deserializer<'de>,
      |    T: Deserialize<'de>,
      |{
      |    let ast: Value = Value::deserialize(deserializer)?;
      |    match ast {
      |        Value::Seq(seq) => {
      |            seq.into_iter()
      |                .map(|v| T::deserialize(ValueDeserializer::<D::Error>::new(v)))
      |                .collect()
      |        }
      |        Value::Unit => Ok(vec![]),
      |        other => {
      |            let single_value: T = Deserialize::deserialize(
      |                ValueDeserializer::<D::Error>::new(other)
      |            ).map_err(D::Error::custom)?;
      |            Ok(vec![single_value])
      |        }
      |    }
      |}
      |
      |
      |pub fn deserialize_primitive_list_or_single_value_optional<'de, D, T>(
      |    deserializer: D
      |) -> Result<Option<Vec<T>>, D::Error>
      |where
      |    D: Deserializer<'de>,
      |    T: Deserialize<'de>,
      |{
      |    let ast: Value = Value::deserialize(deserializer)?;
      |    match ast {
      |        Value::Unit => Ok(None),
      |        _ => {
      |            let d = deserialize_primitive_list_or_single_value(ValueDeserializer::<D::Error>::new(ast))?;
      |            Ok(Some(d))
      |        }
      |    }
      |}
      |
      |#[cfg(all(feature = "serde", feature = "pyo3"))]
      |fn py_any_to_value(bound: &Bound<'_, PyAny>) -> PyResult<Value> {
      |    if bound.is_none() {
      |        return Ok(Value::Unit);
      |    }
      |
      |    if let Ok(b) = bound.extract::<bool>() {
      |        return Ok(Value::Bool(b));
      |    }
      |
      |    if let Ok(i) = bound.extract::<i64>() {
      |        return Ok(Value::I64(i));
      |    }
      |
      |    if let Ok(f) = bound.extract::<f64>() {
      |        return Ok(Value::F64(f));
      |    }
      |
      |    if let Ok(s) = bound.extract::<String>() {
      |        return Ok(Value::String(s));
      |    }
      |
      |    if let Ok(dict_obj) = bound.getattr("__dict__") {
      |        if let Ok(dict) = dict_obj.cast::<PyDict>() {
      |            let mut map: BTreeMap<Value, Value> = BTreeMap::new();
      |            for (k, v) in dict.iter() {
      |                let key_str: String = k.extract()?;
      |                let val = py_any_to_value(&v)?;
      |                map.insert(Value::String(key_str), val);
      |            }
      |            return Ok(Value::Map(map));
      |        }
      |    }
      |
      |    if let Ok(dict) = bound.cast::<PyDict>() {
      |        let mut map: BTreeMap<Value, Value> = BTreeMap::new();
      |        for (k, v) in dict.iter() {
      |            let key_str: String = k.extract()?;
      |            let val = py_any_to_value(&v)?;
      |            map.insert(Value::String(key_str), val);
      |        }
      |        return Ok(Value::Map(map));
      |    }
      |
      |    if let Ok(list) = bound.cast::<PyList>() {
      |        let mut items = Vec::with_capacity(list.len());
      |        for item in list.iter() {
      |            items.push(py_any_to_value(&item)?);
      |        }
      |        return Ok(Value::Seq(items));
      |    }
      |
      |    if let Ok(tuple) = bound.cast::<PyTuple>() {
      |        let mut items = Vec::with_capacity(tuple.len());
      |        for item in tuple.iter() {
      |            items.push(py_any_to_value(&item)?);
      |        }
      |        return Ok(Value::Seq(items));
      |    }
      |
      |    Err(PyErr::new::<pyo3::exceptions::PyTypeError, _>(
      |        "unsupported value type for conversion",
      |    ))
      |}
      |
      |#[cfg(feature = "serde")]
      |#[allow(dead_code)]
      |pub fn serialize_inlined_dict_map<S, T>(
      |    value: &HashMap<T::Key, T>,
      |    serializer: S,
      |) -> Result<S::Ok, S::Error>
      |where
      |    S: Serializer,
      |    T: InlinedPair + Serialize,
      |    T::Key: Serialize + Clone + Ord,
      |    T::Value: Serialize,
      |{
      |    let mut ordered: BTreeMap<T::Key, &T> = BTreeMap::new();
      |    for (k, v) in value.iter() {
      |        ordered.insert(k.clone(), v);
      |    }
      |
      |    let mut as_values: BTreeMap<T::Key, Value> = BTreeMap::new();
      |    for (k, v) in ordered.iter() {
      |        if let Some(compact) = v.compact_value() {
      |            // The short form `key: value` only if no other field is set
      |            let only_value = matches!(&compact, Value::Map(m) if m.len() <= 1);
      |            if let (true, Some(simple)) = (only_value, v.simple_value()) {
      |                let val = to_value(simple)
      |                    .map_err(|e| <S::Error as serde::ser::Error>::custom(e))?;
      |                as_values.insert((*k).clone(), val);
      |            } else {
      |                as_values.insert((*k).clone(), compact);
      |            }
      |            continue;
      |        }
      |        let val = to_value(*v)
      |            .map_err(|e| <S::Error as serde::ser::Error>::custom(e))?;
      |        as_values.insert((*k).clone(), val);
      |    }
      |    as_values.serialize(serializer)
      |}
      |
      |#[cfg(feature = "serde")]
      |#[allow(dead_code)]
      |pub fn serialize_inlined_dict_map_optional<S, T>(
      |    value: &Option<HashMap<T::Key, T>>,
      |    serializer: S,
      |) -> Result<S::Ok, S::Error>
      |where
      |    S: Serializer,
      |    T: InlinedPair + Serialize,
      |    T::Key: Serialize + Clone + Ord,
      |    T::Value: Serialize,
      |{
      |    match value {
      |        Some(map) => serialize_inlined_dict_map(map, serializer),
      |        None => serializer.serialize_none(),
      |    }
      |}
      |
      |#[cfg(feature = "serde")]
      |#[allow(dead_code)]
      |pub fn serialize_inlined_dict_list<S, T>(
      |    value: &Vec<T>,
      |    serializer: S,
      |) -> Result<S::Ok, S::Error>
      |where
      |    S: Serializer,
      |    T: InlinedPair + Serialize,
      |    T::Key: Serialize + Clone + Ord,
      |    T::Value: Serialize,
      |{
      |    let mut ordered: BTreeMap<T::Key, &T> = BTreeMap::new();
      |    for item in value.iter() {
      |        ordered.insert(item.extract_key().clone(), item);
      |    }
      |
      |    let mut as_values: BTreeMap<T::Key, Value> = BTreeMap::new();
      |    for (k, v) in ordered.iter() {
      |        if let Some(compact) = v.compact_value() {
      |            // The short form `key: value` only if no other field is set
      |            let only_value = matches!(&compact, Value::Map(m) if m.len() <= 1);
      |            if let (true, Some(simple)) = (only_value, v.simple_value()) {
      |                let val = to_value(simple)
      |                    .map_err(|e| <S::Error as serde::ser::Error>::custom(e))?;
      |                as_values.insert((*k).clone(), val);
      |            } else {
      |                as_values.insert((*k).clone(), compact);
      |            }
      |            continue;
      |        }
      |        let val = to_value(*v)
      |            .map_err(|e| <S::Error as serde::ser::Error>::custom(e))?;
      |        as_values.insert((*k).clone(), val);
      |    }
      |    as_values.serialize(serializer)
      |}
      |
      |#[cfg(feature = "serde")]
      |#[allow(dead_code)]
      |pub fn serialize_inlined_dict_list_optional<S, T>(
      |    value: &Option<Vec<T>>,
      |    serializer: S,
      |) -> Result<S::Ok, S::Error>
      |where
      |    S: Serializer,
      |    T: InlinedPair + Serialize,
      |    T::Key: Serialize + Clone + Ord,
      |    T::Value: Serialize,
      |{
      |    match value {
      |        Some(items) => serialize_inlined_dict_list(items, serializer),
      |        None => serializer.serialize_none(),
      |    }
      |}
      |
      |#[cfg(feature = "serde")]
      |#[allow(dead_code)]
      |pub fn serialize_primitive_list_or_single_value<S, T>(
      |    value: &Vec<T>,
      |    serializer: S,
      |) -> Result<S::Ok, S::Error>
      |where
      |    S: Serializer,
      |    T: Serialize,
      |{
      |    match value.as_slice() {
      |        [single] => single.serialize(serializer),
      |        _ => value.serialize(serializer),
      |    }
      |}
      |
      |#[cfg(feature = "serde")]
      |#[allow(dead_code)]
      |pub fn serialize_primitive_list_or_single_value_optional<S, T>(
      |    value: &Option<Vec<T>>,
      |    serializer: S,
      |) -> Result<S::Ok, S::Error>
      |where
      |    S: Serializer,
      |    T: Serialize,
      |{
      |    match value {
      |        Some(v) => serialize_primitive_list_or_single_value(v, serializer),
      |        None => serializer.serialize_none(),
      |    }
      |}
      |
      |#[cfg(all(feature = "serde", feature = "pyo3"))]
      |pub fn deserialize_py_any<'py, T>(bound: &Bound<'py, PyAny>) -> PyResult<T>
      |where
      |    T: DeserializeOwned,
      |{
      |    let value = py_any_to_value(bound)?;
      |    let de = value.into_deserializer();
      |    match serde_path_to_error::deserialize(de) {
      |        Ok(ok) => Ok(ok),
      |        Err(err) => Err(PyErr::new::<pyo3::exceptions::PyValueError, _>(
      |            format!("at `{}`: {}", err.path(), err.inner()),
      |        )),
      |    }
      |}
      |
      |#[cfg(feature = "pyo3")]
      |pub struct PyValue<T>(pub T);
      |
      |#[cfg(feature = "pyo3")]
      |impl<T> PyValue<T> {
      |    pub fn into_inner(self) -> T {
      |        self.0
      |    }
      |}
      |
      |#[cfg(all(feature = "pyo3", feature = "stubgen"))]
      |impl<T> ::pyo3_stub_gen::PyStubType for PyValue<T>
      |where
      |    T: ::pyo3_stub_gen::PyStubType,
      |{
      |    fn type_output() -> ::pyo3_stub_gen::TypeInfo {
      |        T::type_output()
      |    }
      |}
      |
      |#[cfg(all(feature = "pyo3", feature = "serde"))]
      |impl<'a, 'py, T> FromPyObject<'a, 'py> for PyValue<T>
      |where
      |    T: DeserializeOwned,
      |{
      |    type Error = PyErr;
      |
      |    fn extract(ob: Borrowed<'a, 'py, PyAny>) -> PyResult<Self> {
      |        deserialize_py_any(&ob).map(PyValue)
      |    }
      |}
      |""".stripMargin

  /** `poly_containers.rs`: the views of lists and maps that the getters of the traits return. */
  val polyContainers: String =
    """use std::{
      |    hash::Hash,
      |    collections::HashMap,
      |    iter::{Map, IntoIterator},
      |    ops::{Index, IndexMut},
      |    slice,
      |};
      |
      |/* ------------------------------------------------------------------- *
      | *  Immutable view
      | * ------------------------------------------------------------------- */
      |
      | pub struct ListView<'a, T> {
      |    inner: &'a [Box<T>],
      |}
      |
      |type ViewIter<'a, T> =
      |    std::iter::Map<
      |        std::slice::Iter<'a, Box<T>>,
      |        fn(&'a Box<T>) -> &'a T,
      |    >;
      |
      |
      |impl<'a, T> ListView<'a, T> {
      |
      |    pub fn len(&self) -> usize        { self.inner.len() }
      |    pub fn is_empty(&self) -> bool    { self.inner.is_empty() }
      |
      |    pub fn get(&self, i: usize) -> Option<&T> {
      |        self.inner.get(i).map(|b| &**b)
      |    }
      |
      |    pub fn new(inner: &'a [Box<T>]) -> ListView<'a, T> {
      |        Self { inner : inner }
      |    }
      |
      |
      |    pub fn iter(&self) -> ViewIter<'a, T> {
      |        self.inner.iter().map(debox)
      |    }
      |}
      |
      |/* Index (`view[i]`) */
      |impl<'a, T> Index<usize> for ListView<'a, T> {
      |    type Output = T;
      |    fn index(&self, i: usize) -> &Self::Output { &*self.inner[i] }
      |}
      |
      |/* &ListView iteration  (`for x in &view`) */
      |type Iter<'a, T> = Map<slice::Iter<'a, Box<T>>, fn(&'a Box<T>) -> &'a T>;
      |fn debox<'a, T>(b: &'a Box<T>) -> &'a T { &**b }
      |
      |impl<'a, T> IntoIterator for &'a ListView<'a, T> {
      |    type Item     = &'a T;
      |    type IntoIter = Iter<'a, T>;
      |    fn into_iter(self) -> Self::IntoIter { self.inner.iter().map(debox) }
      |}
      |
      |/* ------------------------------------------------------------------- *
      | *  Mutable view
      | * ------------------------------------------------------------------- */
      |
      |pub struct ListViewMut<'a, T> {
      |    inner: &'a mut Vec<Box<T>>,
      |}
      |
      |impl<'a, T> ListViewMut<'a, T> {
      |    pub fn len(&self) -> usize        { self.inner.len() }
      |    pub fn is_empty(&self) -> bool    { self.inner.is_empty() }
      |
      |    pub fn new(inner: &'a mut Vec<Box<T>>) -> ListViewMut<'a, T> {
      |        Self { inner : inner }
      |    }
      |
      |    pub fn get(&self, i: usize) -> Option<&T> {
      |        self.inner.get(i).map(|b| &**b)
      |    }
      |    pub fn get_mut(&mut self, i: usize) -> Option<&mut T> {
      |        self.inner.get_mut(i).map(|b| &mut **b)
      |    }
      |
      |    pub fn push(&mut self, v: T) {
      |        self.inner.push(Box::new(v));
      |    }
      |
      |    pub fn iter(&self) -> Iter<'_, T> {
      |        self.inner.iter().map(debox)
      |    }
      |    pub fn iter_mut(&mut self) -> IterMut<'_, T> {
      |        self.inner.iter_mut().map(debox_mut)
      |    }
      |}
      |
      |/* Index / IndexMut */
      |impl<'a, T> Index<usize> for ListViewMut<'a, T> {
      |    type Output = T;
      |    fn index(&self, i: usize) -> &Self::Output { &*self.inner[i] }
      |}
      |impl<'a, T> IndexMut<usize> for ListViewMut<'a, T> {
      |    fn index_mut(&mut self, i: usize) -> &mut Self::Output { &mut *self.inner[i] }
      |}
      |
      |/* &mut ListViewMut iteration (`for x in &mut view`) */
      |fn debox_mut<'a, T>(b: &'a mut Box<T>) -> &'a mut T { &mut **b }
      |type IterMut<'a, T> = Map<slice::IterMut<'a, Box<T>>, fn(&'a mut Box<T>) -> &'a mut T>;
      |
      |impl<'a, T> IntoIterator for &'a ListViewMut<'a, T> {
      |    type Item     = &'a T;
      |    type IntoIter = Iter<'a, T>;
      |    fn into_iter(self) -> Self::IntoIter { self.inner.iter().map(debox) }
      |}
      |impl<'a, T> IntoIterator for &'a mut ListViewMut<'a, T> {
      |    type Item     = &'a mut T;
      |    type IntoIter = IterMut<'a, T>;
      |    fn into_iter(self) -> Self::IntoIter { self.inner.iter_mut().map(debox_mut) }
      |}
      |
      |
      |pub struct MapView<'a, K, V> {
      |    inner: &'a HashMap<K, Box<V>>,
      |}
      |
      |impl<'a, K: Eq + Hash, V> MapView<'a, K, V> {
      |    /* basic info */
      |    pub fn len(&self) -> usize        { self.inner.len() }
      |    pub fn is_empty(&self) -> bool    { self.inner.is_empty() }
      |
      |    /* look-ups */
      |    pub fn get(&self, k: &K) -> Option<&V> {
      |        self.inner.get(k).map(debox)
      |    }
      |
      |    pub fn new(inner: &'a HashMap<K, Box<V>>)  -> Self {
      |        Self { inner : inner }
      |    }
      |
      |    pub fn iter(&self) -> impl Iterator<Item = (&K, &V)> + '_ {
      |        self.inner.iter().map(as_pair)
      |    }
      |
      |}
      |
      |/* &MapView iteration */
      |type MapIter<'a, K, V> =
      |    Map<std::collections::hash_map::Iter<'a, K, Box<V>>, fn((&'a K,&'a Box<V>)) -> (&'a K,&'a V)>;
      |
      |fn as_pair<'a, K, V>((k, v): (&'a K, &'a Box<V>)) -> (&'a K, &'a V) { (k, &**v) }
      |
      |impl<'a, K: Eq + Hash, V> IntoIterator for &'a MapView<'a, K, V> {
      |    type Item     = (&'a K, &'a V);
      |    type IntoIter = MapIter<'a, K, V>;
      |    fn into_iter(self) -> Self::IntoIter { self.inner.iter().map(as_pair) }
      |}
      |
      |/* ------------------------------- mutable view ---------------------- */
      |pub struct MapViewMut<'a, K, V> {
      |    inner: &'a mut HashMap<K, Box<V>>,
      |}
      |
      |impl<'a, K: Eq + Hash, V> MapViewMut<'a, K, V> {
      |    /* same basic info */
      |    pub fn len(&self) -> usize        { self.inner.len() }
      |    pub fn is_empty(&self) -> bool    { self.inner.is_empty() }
      |
      |    /* look-ups */
      |    pub fn get(&self,  k: &K) -> Option<&V>       { self.inner.get(k).map(debox) }
      |    pub fn get_mut(&mut self, k: &K) -> Option<&mut V> { self.inner.get_mut(k).map(debox_mut) }
      |
      |    /* insertion / removal */
      |    pub fn insert(&mut self, k: K, v: V) -> Option<V> {
      |        self.inner.insert(k, Box::new(v)).map(|old| *old)
      |    }
      |    pub fn remove(&mut self, k: &K) -> Option<V> {
      |        self.inner.remove(k).map(|b| *b)
      |    }
      |
      |    /* iterators */
      |    pub fn iter<'b>(&'b self) -> MapIter<'b, K, V> {
      |        self.inner.iter().map(as_pair)
      |    }
      |    pub fn iter_mut<'b>(&'b mut self) -> MapIterMut<'b, K, V> {
      |        self.inner.iter_mut().map(as_pair_mut)
      |    }
      |}
      |
      |/* &mut MapViewMut iteration */
      |type MapIterMut<'a, K, V> =
      |    Map<std::collections::hash_map::IterMut<'a, K, Box<V>>,
      |        fn((&'a K,&'a mut Box<V>)) -> (&'a K,&'a mut V)>;
      |
      |fn as_pair_mut<'a, K, V>((k, v): (&'a K, &'a mut Box<V>)) -> (&'a K, &'a mut V) { (k, &mut **v) }
      |
      |impl<'a, K: Eq + Hash, V> IntoIterator for &'a mut MapViewMut<'a, K, V> {
      |    type Item     = (&'a K, &'a mut V);
      |    type IntoIter = MapIterMut<'a, K, V>;
      |    fn into_iter(self) -> Self::IntoIter { self.inner.iter_mut().map(as_pair_mut) }
      |}
      |/* also allow &MapViewMut to iterate immutably */
      |impl<'a, K: Eq + Hash, V> IntoIterator for &'a MapViewMut<'a, K, V> {
      |    type Item     = (&'a K, &'a V);
      |    type IntoIter = MapIter<'a, K, V>;
      |    fn into_iter(self) -> Self::IntoIter { self.inner.iter().map(as_pair) }
      |}
      |
      |
      |pub trait MapRef<'l, K, V> {
      |    fn get(&self, k: &K) -> Option<&V>;
      |    fn len(&self) -> usize;
      |    fn is_empty(&self) -> bool { self.len() == 0 }
      |
      |    type Iter<'a>: Iterator<Item = (&'a K, &'a V)>
      |    where
      |        Self: 'a,
      |        K: 'a,
      |        V: 'a;
      |
      |    fn iter(&self) -> Self::Iter<'_>;
      |
      |    fn to_any(self) -> MapAny<'l, K, V>
      |    where
      |        Self: Sized;        // keeps it object-safe enough for static dispatch
      |}
      |
      |
      |/* NEW — borrowed map */
      |impl<'a, K: Eq + Hash, V> MapRef<'a, K, V> for &'a HashMap<K, V> {
      |    fn get(&self, k: &K) -> Option<&V> { (*self).get(k) }
      |    fn len(&self) -> usize             { (*self).len() }
      |
      |    type Iter<'b> = std::collections::hash_map::Iter<'b, K, V>
      |    where
      |        Self: 'b,
      |        K: 'b,
      |        V: 'b;
      |
      |    fn iter(&self) -> Self::Iter<'a> {
      |        (*self).iter()
      |    }
      |    fn to_any(self) -> MapAny<'a, K, V>
      |    where
      |        Self: Sized
      |     {
      |        MapAny::Hash(self)
      |    }
      |}
      |
      |/* ---- impl for your borrowing MapView ---------------------------- */
      |impl<'a, K: std::hash::Hash + Eq, V> MapRef<'a, K, V> for MapView<'a, K, V> {
      |    fn get(&self, k: &K) -> Option<&V> { self.inner.get(k).map(|b| &**b) }
      |    fn len(&self) -> usize { self.inner.len() }
      |
      |    type Iter<'b> =
      |        std::iter::Map<
      |            std::collections::hash_map::Iter<'b, K, Box<V>>,
      |            fn((&'b K,&'b Box<V>)) -> (&'b K,&'b V)
      |        > where K: 'b, V: 'b, Self: 'b;
      |
      |    fn iter(&self) -> Self::Iter<'_> {
      |        fn as_pair<'b, K, V>((k, v): (&'b K, &'b Box<V>)) -> (&'b K, &'b V) { (k, &**v) }
      |        self.inner.iter().map(as_pair)
      |    }
      |    fn to_any(self) -> MapAny<'a, K, V>
      |    where
      |        Self: Sized
      |     {
      |        MapAny::View(MapView { inner : self.inner })
      |    }
      |}
      |
      |
      |
      |pub trait SeqRef<'l, T> {
      |    /// Immutable iterator (`for x in seq.iter()`)
      |    type Iter<'a>: Iterator<Item = &'a T> + ExactSizeIterator
      |    where
      |        Self: 'a,
      |        T: 'a;
      |
      |    /// Number of elements
      |    fn len(&self) -> usize;
      |
      |    /// Immutable indexing
      |    fn get(&self, i: usize) -> Option<&T>;
      |
      |    /// Borrowing iterator
      |    fn iter(&self) -> Self::Iter<'l>;
      |
      |    /// Convenience
      |    fn is_empty(&self) -> bool { self.len() == 0 }
      |
      |    fn to_any(self) -> ListAny<'l, T>
      |    where
      |        Self: Sized;
      |}
      |
      |
      |impl<'l, T> SeqRef<'l, T> for &'l Vec<T> {
      |    type Iter<'a> = slice::Iter<'a, T> where T: 'a, Self: 'a;
      |
      |    fn len(&self) -> usize              { Vec::len(self) }
      |    fn get(&self, i: usize) -> Option<&T> { self.as_slice().get(i) }
      |    fn iter(&self) -> Self::Iter<'l>    { self.as_slice().iter() }
      |    fn to_any(self) -> ListAny<'l, T>
      |    where
      |        Self: Sized,
      |    {
      |        ListAny::Vec(self)
      |    }
      |}
      |
      |
      |impl<'a, T> SeqRef<'a, T> for ListView<'a, T> {
      |    type Iter<'b> = Iter<'b, T> where Self: 'b, T: 'b;
      |
      |    fn len(&self) -> usize              { self.inner.len() }
      |    fn get(&self, i: usize) -> Option<&T> { self.inner.get(i).map(debox) }
      |    fn iter(&self) -> Self::Iter<'a>    { self.inner.iter().map(debox) }
      |    fn to_any(self) -> ListAny<'a, T>
      |    where
      |        Self: Sized,
      |    {
      |        ListAny::View(ListView { inner:  self.inner })
      |    }
      |}
      |
      |pub enum MapAny<'a, K, V> {
      |    Hash(&'a std::collections::HashMap<K, V>),   // &HashMap
      |    View(MapView<'a, K, V>),               // MapView (already borrows)
      |}
      |
      |impl<'a, K: Eq + std::hash::Hash, V> MapRef<'a, K, V>
      |    for MapAny<'a, K, V>
      |{
      |    type Iter<'b> =
      |        std::boxed::Box<dyn Iterator<Item = (&'b K, &'b V)> + 'b>
      |        where Self: 'b, K: 'b, V: 'b;
      |
      |    fn len(&self) -> usize {
      |        match self {
      |            Self::Hash(m) => m.len(),
      |            Self::View(v) => v.len(),
      |        }
      |    }
      |    fn get(&self, k: &K) -> Option<&V> {
      |        match self {
      |            Self::Hash(m) => m.get(k),
      |            Self::View(v) => v.get(k),
      |        }
      |    }
      |    fn iter(&self) -> Self::Iter<'_> {
      |        match self {
      |            Self::Hash(m) => Box::new(m.iter()),
      |            Self::View(v) => Box::new(v.iter()),
      |        }
      |    }
      |    fn to_any(self) -> MapAny<'a, K, V>
      |        where
      |            Self: Sized,
      |             {
      |        match self {
      |            Self::Hash(m) => MapAny::Hash(m),
      |            Self::View(v) => MapAny::View(v),
      |         }
      |    }
      |}
      |
      |
      |pub enum ListIter<'a, T> {
      |    Vec  (std::slice::Iter<'a, T>),
      |    View (ViewIter<'a, T>),
      |}
      |
      |impl<'a, T> Iterator for ListIter<'a, T> {
      |    type Item = &'a T;
      |    fn next(&mut self) -> Option<Self::Item> {
      |        match self {
      |            Self::Vec(it)  => it.next(),
      |            Self::View(it) => it.next(),
      |        }
      |    }
      |    fn size_hint(&self) -> (usize, Option<usize>) {
      |        match self {
      |            Self::Vec(it)  => it.size_hint(),
      |            Self::View(it) => it.size_hint(),
      |        }
      |    }
      |}
      |
      |impl<'a, T> ExactSizeIterator for ListIter<'a, T> {
      |    fn len(&self) -> usize {
      |        match self {
      |            Self::Vec(it)  => it.len(),
      |            Self::View(it) => it.len(),
      |        }
      |    }
      |}
      |
      |pub enum ListAny<'a, T> {
      |    Vec(&'a Vec<T>),                  // &Vec<T>
      |    View(ListView<'a, T>),        // ListView (already borrows)
      |}
      |
      |/* ------------------------------------------------------------------ *
      | *  SeqRef implementation
      | * ------------------------------------------------------------------ */
      |impl<'a, T> SeqRef<'a, T> for ListAny<'a, T> {
      |    type Iter<'x> = ListIter<'x, T> where Self: 'x, T: 'x;
      |    //type Iter<'b> =
      |    //    std::boxed::Box<dyn Iterator<Item = &'b T> + ExactSizeIterator + 'b>
      |    //    where Self: 'b, T: 'b;
      |
      |    fn len(&self) -> usize {
      |        match self {
      |            Self::Vec(v)  => v.len(),
      |            Self::View(v) => v.len(),
      |        }
      |    }
      |    fn get(&self, i: usize) -> Option<&T> {
      |        match self {
      |            Self::Vec(v)  => v.get(i),
      |            Self::View(v) => v.get(i),
      |        }
      |    }
      |    fn iter(&self) -> Self::Iter<'a> {
      |        match self {
      |            Self::Vec(v)  => ListIter::Vec(v.iter()),
      |            Self::View(v) => ListIter::View(v.iter()),
      |        }
      |    }
      |    fn to_any(self) -> ListAny<'a, T>
      |    where
      |        Self: Sized,
      |         {
      |            match self {
      |                Self::Vec(v)  => ListAny::Vec(v),
      |                Self::View(v) => ListAny::View(v),
      |            }
      |          }
      |}
      |""".stripMargin

  /** `stub_utils.rs`: checks of the module names of the Python type stubs. */
  val stubUtils: String =
    """use std::io::{Error, ErrorKind};
      |use std::path::{Component, PathBuf};
      |
      |/// Convert a stub module name into a relative filesystem path.
      |///
      |/// The generator currently expects module names that mirror Python package
      |/// semantics (``foo.bar``) with optional hyphens. Hyphens are replaced with
      |/// underscores and dots map to directory separators. The helper rejects
      |/// absolute paths, traversal markers, or characters that cannot appear in
      |/// stub files so we never escape the designated stub root.
      |pub fn normalize_stub_module(name: &str) -> std::io::Result<PathBuf> {
      |    if name.is_empty() {
      |        return Err(Error::new(
      |            ErrorKind::InvalidInput,
      |            "module name may not be empty",
      |        ));
      |    }
      |
      |    let normalized = name.replace('-', "_");
      |    let path_str = normalized.replace('.', "/");
      |    let path = PathBuf::from(&path_str);
      |
      |    if path.is_absolute() {
      |        return Err(Error::new(
      |            ErrorKind::InvalidInput,
      |            format!("absolute module path `{name}` not allowed"),
      |        ));
      |    }
      |
      |    for component in path.components() {
      |        match component {
      |            Component::Normal(part) => {
      |                let segment = part.to_string_lossy();
      |                if segment.is_empty() {
      |                    return Err(Error::new(
      |                        ErrorKind::InvalidInput,
      |                        format!("module name `{name}` contains an empty segment"),
      |                    ));
      |                }
      |                if segment == "." || segment == ".." {
      |                    return Err(Error::new(
      |                        ErrorKind::InvalidInput,
      |                        format!(
      |                            "module name `{name}` contains disallowed segment `{segment}`"
      |                        ),
      |                    ));
      |                }
      |                if segment
      |                    .chars()
      |                    .any(|ch| matches!(ch, '*' | '?' | '<' | '>' | '|' | ':' | '\\' | '/'))
      |                {
      |                    return Err(Error::new(
      |                        ErrorKind::InvalidInput,
      |                        format!(
      |                            "module name `{name}` contains invalid characters in `{segment}`"
      |                        ),
      |                    ));
      |                }
      |            }
      |            Component::CurDir | Component::ParentDir => {
      |                return Err(Error::new(
      |                    ErrorKind::InvalidInput,
      |                    format!("module name `{name}` attempts directory traversal"),
      |                ));
      |            }
      |            Component::RootDir | Component::Prefix(_) => {
      |                return Err(Error::new(
      |                    ErrorKind::InvalidInput,
      |                    format!("module name `{name}` resolves outside stub root"),
      |                ));
      |            }
      |        }
      |    }
      |
      |    Ok(path)
      |}
      |""".stripMargin

  /** `src/bin/stub_gen.rs`: the program that writes the Python type stubs. */
  def stubGen(crate: String): String =
    """#[cfg(feature = "stubgen")]
      |use CRATE_NAME::stub_utils;
      |
      |#[cfg(feature = "stubgen")]
      |fn main() -> pyo3_stub_gen::Result<()> {
      |    let check_only = std::env::args().skip(1).any(|arg| arg == "--check");
      |    let stub = CRATE_NAME::stub_info()?;
      |
      |    if check_only {
      |        check_stubs(&stub)
      |    } else {
      |        stub.generate()
      |    }
      |}
      |
      |#[cfg(feature = "stubgen")]
      |fn check_stubs(stub: &pyo3_stub_gen::StubInfo) -> pyo3_stub_gen::Result<()> {
      |    use std::fs;
      |    use std::io::ErrorKind;
      |
      |    let mut issues = Vec::new();
      |
      |    for (name, module) in &stub.modules {
      |        let module_path = stub_utils::normalize_stub_module(name)?;
      |        let mut dest = stub.python_root.join(&module_path);
      |        if module.submodules.is_empty() {
      |            dest.set_extension("pyi");
      |        } else {
      |            dest.push("__init__.pyi");
      |        }
      |
      |        let expected = module.to_string();
      |        match fs::read_to_string(&dest) {
      |            Ok(actual) => {
      |                if actual != expected {
      |                    issues.push(format!(
      |                        "updated content differs for `{}` (module `{name}`)",
      |                        dest.display()
      |                    ));
      |                }
      |            }
      |            Err(err) if err.kind() == ErrorKind::NotFound => {
      |                issues.push(format!(
      |                    "missing stub file `{}` for module `{name}`",
      |                    dest.display()
      |                ));
      |            }
      |            Err(err) => return Err(err.into()),
      |        }
      |    }
      |
      |    if issues.is_empty() {
      |        Ok(())
      |    } else {
      |        let mut msg = String::from("Stub files are out of date:\n");
      |        for issue in issues {
      |            msg.push_str("  - ");
      |            msg.push_str(&issue);
      |            msg.push('\n');
      |        }
      |        msg.push_str("Run `cargo run --bin stub_gen --features stubgen` to regenerate.");
      |        Err(std::io::Error::new(ErrorKind::Other, msg).into())
      |    }
      |}
      |
      |#[cfg(not(feature = "stubgen"))]
      |fn main() {
      |    eprintln!(
      |        "Enable the `stubgen` feature (alongside `pyo3`) to run this generator."
      |    );
      |}
      |""".stripMargin.replace("CRATE_NAME", crate)

  /** The struct of a class with the `linkml:Any` URI and no slots. It wraps a `serde_value::Value`,
    * so it reads any YAML or JSON value.
    */
  def anything(name: String): String =
    """#[derive(Clone, PartialEq)]
      |pub struct Anything(
      |    #[cfg(feature = "serde")] pub serde_value::Value,
      |    #[cfg(not(feature = "serde"))] pub (),
      |);
      |
      |
      |#[cfg(feature = "stubgen")]
      |impl ::pyo3_stub_gen::PyStubType for Anything {
      |    fn type_output() -> ::pyo3_stub_gen::TypeInfo {
      |        ::pyo3_stub_gen::TypeInfo::any()
      |    }
      |}
      |
      |#[cfg(feature = "stubgen")]
      |impl ::pyo3_stub_gen::runtime::PyRuntimeType for Anything {
      |    fn runtime_type_object(py: pyo3::Python<'_>) -> pyo3::PyResult<pyo3::Bound<'_, pyo3::PyAny>> {
      |        // Anything maps to `object` at runtime, like PyAny.
      |        Ok(py.get_type::<pyo3::types::PyAny>().into_any())
      |    }
      |}
      |
      |
      |#[cfg(feature = "serde")]
      |impl Serialize for Anything {
      |    fn serialize<S>(&self, to_ser: S) -> Result<S::Ok, S::Error>
      |    where S: serde::Serializer {
      |        self.0.serialize(to_ser)
      |    }
      |}
      |
      |#[cfg(feature = "serde")]
      |impl<'de> Deserialize<'de> for Anything {
      |    fn deserialize<D>(de: D) -> Result<Self, D::Error>
      |    where D: serde::Deserializer<'de> {
      |        <serde_value::Value as Deserialize>::deserialize(de).map(Anything)
      |    }
      |}
      |
      |#[cfg(all(feature = "pyo3", feature = "serde"))]
      |impl<'a, 'py> FromPyObject<'a, 'py> for Anything {
      |    type Error = PyErr;
      |    fn extract(obj: pyo3::Borrowed<'a, 'py, pyo3::types::PyAny>) -> pyo3::PyResult<Self> {
      |        use pyo3::types::{PyAny, PyDict, PyList, PyTuple};
      |        use serde_value::Value;
      |
      |        fn py_to_value<'py>(o: &pyo3::Bound<'py, PyAny>) -> pyo3::PyResult<Value> {
      |            // None -> Unit
      |            if o.is_none() {
      |                return Ok(Value::Unit);
      |            }
      |
      |            // Try simple primitives first.
      |            // Order matters: Python's `bool` is a subclass of `int`, so `bool`
      |            // must be tried before `i64`. Likewise, `i64` before `f64` so we
      |            // don't widen integers to floats.
      |            if let Ok(b) = o.extract::<bool>() {
      |                return Ok(Value::Bool(b));
      |            }
      |            if let Ok(i) = o.extract::<i64>() {
      |                return Ok(Value::I64(i));
      |            }
      |            if let Ok(f) = o.extract::<f64>() {
      |                return Ok(Value::F64(f));
      |            }
      |            if let Ok(s) = o.extract::<&str>() {
      |                return Ok(Value::String(s.to_string()));
      |            }
      |
      |            // Sequences (list/tuple)
      |            if let Ok(list) = o.cast::<PyList>() {
      |                let mut out = Vec::with_capacity(list.len());
      |                for item in list.iter() {
      |                    out.push(py_to_value(&item)?);
      |                }
      |                return Ok(Value::Seq(out));
      |            }
      |            if let Ok(t) = o.cast::<PyTuple>() {
      |                let mut out = Vec::with_capacity(t.len());
      |                for item in t.iter() {
      |                    out.push(py_to_value(&item)?);
      |                }
      |                return Ok(Value::Seq(out));
      |            }
      |
      |            // Mappings (dict with string-like keys)
      |            if let Ok(d) = o.cast::<PyDict>() {
      |                let mut map = std::collections::BTreeMap::<Value, Value>::new();
      |                for (k, v) in d.iter() {
      |                    // Only accept string-like keys for deterministic ordering
      |                    if let Ok(ks) = k.extract::<&str>() {
      |                        map.insert(Value::String(ks.to_string()), py_to_value(&v)?);
      |                    } else {
      |                        return Err(pyo3::exceptions::PyTypeError::new_err(
      |                            "dict keys for Anything must be str",
      |                        ));
      |                    }
      |                }
      |                return Ok(Value::Map(map));
      |            }
      |
      |            // Fallback: stringify unknown types
      |            let s = format!("{}", o.str()?);
      |            Ok(Value::String(s))
      |        }
      |
      |        Ok(Anything(py_to_value(&obj)?))
      |    }
      |}
      |
      |/* ---------- getter side ---------- */
      |#[cfg(all(feature = "pyo3", feature = "serde"))]
      |impl<'py> IntoPyObject<'py> for Anything {
      |    type Target = PyAny;
      |    type Output = Bound<'py, Self::Target>;
      |    type Error = PyErr;
      |
      |    fn into_pyobject(self, py: Python<'py>) -> Result<Self::Output, Self::Error> {
      |        use pyo3::types::{PyAny, PyBytes, PyDict, PyList, PyString};
      |        use serde_value::Value;
      |
      |        fn value_to_py<'py>(py: Python<'py>, v: &Value) -> pyo3::PyResult<Bound<'py, PyAny>> {
      |            match v {
      |                Value::Unit => Ok(py.None().into_bound(py)),
      |                Value::Bool(b) => Ok(pyo3::types::PyBool::new(py, *b).to_owned().into_any()),
      |                Value::String(s) => Ok(PyString::new(py, s).into_any()),
      |                Value::U8(n) => Ok(n.into_pyobject(py)?.into_any()),
      |                Value::U16(n) => Ok(n.into_pyobject(py)?.into_any()),
      |                Value::U32(n) => Ok(n.into_pyobject(py)?.into_any()),
      |                Value::U64(n) => Ok(n.into_pyobject(py)?.into_any()),
      |                Value::I8(n) => Ok(n.into_pyobject(py)?.into_any()),
      |                Value::I16(n) => Ok(n.into_pyobject(py)?.into_any()),
      |                Value::I32(n) => Ok(n.into_pyobject(py)?.into_any()),
      |                Value::I64(n) => Ok(n.into_pyobject(py)?.into_any()),
      |                Value::F32(n) => Ok(n.into_pyobject(py)?.into_any()),
      |                Value::F64(n) => Ok(n.into_pyobject(py)?.into_any()),
      |                Value::Char(c) => Ok(PyString::new(py, &c.to_string()).into_any()),
      |                Value::Bytes(b) => Ok(PyBytes::new(py, b).into_any()),
      |                Value::Option(opt) => match opt {
      |                    Some(inner) => value_to_py(py, inner),
      |                    None => Ok(py.None().into_bound(py)),
      |                },
      |                Value::Newtype(inner) => value_to_py(py, inner),
      |                Value::Seq(seq) => {
      |                    let list = PyList::empty(py);
      |                    for item in seq.iter() {
      |                        let ob = value_to_py(py, item)?;
      |                        list.append(ob)?;
      |                    }
      |                    Ok(list.into_any())
      |                }
      |                Value::Map(map) => {
      |                    let dict = PyDict::new(py);
      |                    for (k, v) in map.iter() {
      |                        let pk = value_to_py(py, k)?;
      |                        let pv = value_to_py(py, v)?;
      |                        dict.set_item(pk, pv)?;
      |                    }
      |                    Ok(dict.into_any())
      |                }
      |            }
      |        }
      |
      |        value_to_py(py, &self.0)
      |    }
      |}
      |
      |impl std::fmt::Debug for Anything {
      |    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
      |        #[cfg(feature = "serde")]
      |        return write!(f, "Anything({:?})", self.0);
      |
      |        #[cfg(not(feature = "serde"))]
      |        return f.write_str("Anything(<opaque>)");
      |    }
      |}
      |""".stripMargin.replace("Anything", name)

  final val localizedTextName = "LocalizedText"

  /** The enum for `langstring` values: a plain string, or a map from language tag to text. */
  val localizedText: String =
    """/// A `langstring` value: a plain string, or a map from language tag to text.
      |#[derive(Debug, Clone, PartialEq)]
      |#[cfg_attr(feature = "serde", derive(Serialize, Deserialize))]
      |#[cfg_attr(feature = "serde", serde(untagged))]
      |pub enum LocalizedText {
      |    Plain(String),
      |    Localized(BTreeMap<String, String>),
      |}
      |
      |impl LocalizedText {
      |    /// The plain string, or else the text in `en`, or else the text of the alphabetically
      |    /// first language tag.
      |    pub fn text(&self) -> &str {
      |        match self {
      |            LocalizedText::Plain(text) => text,
      |            LocalizedText::Localized(texts) => texts
      |                .get("en")
      |                .or_else(|| texts.values().next())
      |                .map_or("", String::as_str),
      |        }
      |    }
      |}
      |
      |impl core::fmt::Display for LocalizedText {
      |    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
      |        f.write_str(self.text())
      |    }
      |}
      |
      |#[cfg(feature = "pyo3")]
      |impl<'a, 'py> FromPyObject<'a, 'py> for LocalizedText {
      |    type Error = PyErr;
      |    fn extract(ob: pyo3::Borrowed<'a, 'py, pyo3::types::PyAny>) -> pyo3::PyResult<Self> {
      |        if let Ok(text) = ob.extract::<String>() {
      |            return Ok(LocalizedText::Plain(text));
      |        }
      |        if let Ok(texts) = ob.extract::<BTreeMap<String, String>>() {
      |            return Ok(LocalizedText::Localized(texts));
      |        }
      |        Err(PyErr::new::<pyo3::exceptions::PyTypeError, _>(
      |            "expected str or dict[str, str] for LocalizedText",
      |        ))
      |    }
      |}
      |
      |#[cfg(feature = "pyo3")]
      |impl<'py> IntoPyObject<'py> for LocalizedText {
      |    type Target = PyAny;
      |    type Output = Bound<'py, Self::Target>;
      |    type Error = PyErr;
      |    fn into_pyobject(self, py: Python<'py>) -> Result<Self::Output, Self::Error> {
      |        match self {
      |            LocalizedText::Plain(text) => Ok(text.into_pyobject(py)?.into_any()),
      |            LocalizedText::Localized(texts) => Ok(texts.into_pyobject(py)?.into_any()),
      |        }
      |    }
      |}
      |
      |#[cfg(feature = "stubgen")]
      |::pyo3_stub_gen::impl_stub_type!(LocalizedText = String | BTreeMap<String, String>);
      |""".stripMargin

  val overwriteExceptNone: String =
    """
      |/// Overwrite `left` with `right` unless `right` is `None`.
      |fn overwrite_except_none<T>(left: &mut Option<T>, right: Option<T>) {
      |    if right.is_some() {
      |        *left = right;
      |    }
      |}
      |""".stripMargin

  val optionMapOverwrite: String =
    """
      |/// Merge two `Option<HashMap<K, V>>`s per key: `right` wins on a key conflict,
      |/// keys only present on `left` are preserved, and `None` on `right` is a no-op.
      |fn option_map_overwrite<K: std::cmp::Eq + std::hash::Hash, V>(
      |    left: &mut Option<std::collections::HashMap<K, V>>,
      |    right: Option<std::collections::HashMap<K, V>>,
      |) {
      |    match (left.as_mut(), right) {
      |        (Some(l), Some(r)) => l.extend(r),
      |        (None, r @ Some(_)) => *left = r,
      |        _ => {}
      |    }
      |}
      |""".stripMargin
}
