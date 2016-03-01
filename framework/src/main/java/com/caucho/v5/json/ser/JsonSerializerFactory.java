/*
 * Copyright (c) 1998-2015 Caucho Technology -- all rights reserved
 *
 * This file is part of Baratine(TM)
 *
 * Each copy or derived work must preserve the copyright notice and this
 * notice unmodified.
 *
 * Baratine is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * Baratine is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE, or any warranty
 * of NON-INFRINGEMENT.  See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Baratine; if not, write to the
 *
 *   Free Software Foundation, Inc.
 *   59 Temple Place, Suite 330
 *   Boston, MA 02111-1307  USA
 *
 * @author Scott Ferguson
 */

package com.caucho.v5.json.ser;

import java.io.StringReader;
import java.io.Writer;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.Date;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import com.caucho.v5.inject.type.TypeRef;
import com.caucho.v5.json.io.JsonReader;
import com.caucho.v5.json.io.JsonWriter;
import com.caucho.v5.json.value.JsonValue;
import com.caucho.v5.reflect.ClassImpl;
import com.caucho.v5.reflect.TypeFactoryReflect;

public class JsonSerializerFactory
{
  private static final HashMap<Class<?>,JsonSerializer> _staticSerMap
    = new HashMap<>();
    
  private static final HashMap<Class<?>,JsonSerializer> _staticSerInterfaceMap
    = new HashMap<>();

  private static final HashMap<Class<?>,JsonDeserializer> _staticDeserMap
    = new HashMap<>();
  
  private static final HashMap<Class<?>,CollectionFunction> _factoryMap
    = new HashMap<>();

  /*
  private final ConcurrentHashMap<Class<?>,JsonSerializer> _serMap
    = new ConcurrentHashMap<>();
    */
  
  private final ClassValue<JsonSerializer<?>> _serMap
    = new SerializerClassValue();
    
  private final HashMap<Class<?>,JsonSerializer> _serInterfaceMap
    = new HashMap<>();

  private final TypeFactoryReflect _typeFactory = new TypeFactoryReflect();

  private final ConcurrentHashMap<Type,JsonDeserializer> _deserMap
    = new ConcurrentHashMap<>();

  public JsonSerializerFactory()
  {
  }

  public TypeFactoryReflect getTypeFactory()
  {
    return _typeFactory;
  }
  
  //
  // serializers
  //

  public final <T> JsonSerializer<T> serializer(Class<T> cl)
  {
    return (JsonSerializer) _serMap.get(cl);
    /*
    JsonSerializer ser = _serMap.get(cl);

    if (ser == null) {
      ser = createSerializer(cl);

      _serMap.putIfAbsent(cl, ser);
    }

    return ser;
    */
  }
  
  public <T> void addInterfaceSerializer(Class<T> cl, JsonSerializer<T> ser)
  {
    _serInterfaceMap.put(cl, ser);
  }

  public <T> void addSerializer(Class<T> cl,
                                JsonSerializer<T> ser)
  {
    System.out.println("ADDSER: " + cl + " " + ser);
    //_serMap.put(cl, ser);
  }

  public void addDeserializer(Class<?> cl,
                              JsonDeserializer deser)
  {
    _deserMap.put(cl, deser);
  }

  protected JsonSerializer<?> createSerializer(Class<?> cl)
  {
    JsonSerializer<?> ser = _staticSerMap.get(cl);

    if (ser != null) {
      return ser;
    }

    if (cl.isArray()) {
      return ObjectArraySerializer.SER;
    }
    
    Method methodReplaceObject = findWriteReplace(cl);
    
    if (methodReplaceObject != null) {
      return new JavaSerializerWriteReplace(methodReplaceObject);
    }
    
    if (Collection.class.isAssignableFrom(cl)) {
      return CollectionSerializer.SER;
    }

    if (Map.class.isAssignableFrom(cl)) {
      return MapSerializer.SER;
    }
    
    ser = findInterfaceSerializer(cl);
    
    if (ser != null) {
      return ser;
    }

    if (AtomicInteger.class.isAssignableFrom(cl)) {
      return AtomicIntegerSerializer.SER;
    }

    if (Enum.class.isAssignableFrom(cl)) {
      return EnumSerializer.SER;
    }

    return new JavaSerializer(cl, this);
  }
  
  protected JsonSerializer<?> findInterfaceSerializer(Class<?> cl)
  {
    if (cl == null) {
      return null;
    }

    JsonSerializer<?> ser;
    
    if (cl.isInterface()) {
      ser = _serInterfaceMap.get(cl);
      
      if (ser != null) {
        return ser;
      }
      
      ser = _staticSerInterfaceMap.get(cl);
      
      if (ser != null) {
        return ser;
      }
    }
    
    for (Class<?> iface : cl.getInterfaces()) {
      ser = findInterfaceSerializer(iface);
      
      if (ser != null) {
        return ser;
      }
    }
    
    if (cl.isInterface()) {
      return findInterfaceSerializer(cl.getSuperclass());
    }
    else {
      return null;
    }
  }

  //
  // deserializers
  //

  public JsonDeserializer deserializer(Type type)
  {
    Objects.requireNonNull(type);

    if (type instanceof ClassImpl) {
      type = ((ClassImpl) type).getTypeClass();
    }

    JsonDeserializer deser = _deserMap.get(type);

    if (deser == null) {
      deser = createDeserializer(type);

      _deserMap.putIfAbsent(type, deser);
    }

    return deser;
  }

  protected JsonDeserializer createDeserializer(Type type)
  {    
    JsonDeserializer deser = _staticDeserMap.get(type);

    if (deser != null) {
      return deser;
    }

    TypeRef typeImpl = TypeRef.of(type);

    Class<?> cl = typeImpl.rawClass();
    
    if (Enum.class.isAssignableFrom(cl)) {
      return new EnumDeserializer(cl);
    }
    else if (Object.class.equals(cl)) {
      return _staticDeserMap.get(Object.class);
    }
    
    CollectionFunction collFun = _factoryMap.get(cl);
    
    if (collFun != null) {
      return collFun.apply(typeImpl, this);
    }
    
    /*
    if (cl.isInterface()
        && (Collection.class.isAssignableFrom(cl)
            || Iterable.class.equals(cl))) {
      TypeRef typeArg;
      
      if (Collection.class.isAssignableFrom(cl)) {
        typeArg = typeImpl.to(Collection.class).param(0);
      }
      else if (Iterable.class.isAssignableFrom(cl)) {
        typeArg = typeImpl.to(Iterable.class).param(0);
      }
      else {
        throw new IllegalStateException(String.valueOf(cl));
      }
      
      Supplier<Collection<Object>> factory = (Supplier) _factoryMap.get(cl);

      if (factory != null) {
        return new CollectionDeserializer(getDeserializer(typeArg.type()), factory);
      }
    }
    */

    /*
    if (cl.isInterface() && Iterator.class.equals(cl)) {
      TypeRef typeArg = typeImpl.to(Iterator.class).param(0);
      Supplier<Collection<Object>> factory = (Supplier) _factoryMap.get(cl);

      if (factory != null) {
        return new IteratorDeserializer(getDeserializer(typeArg.type()), factory);
      }
    }

    if (cl.isInterface() && Enumeration.class.equals(cl)) {
      TypeRef typeArg = typeImpl.to(Enumeration.class).param(0);
      Supplier<Collection<Object>> factory = (Supplier) _factoryMap.get(cl);

      if (factory != null) {
        return new EnumerationDeserializer(getDeserializer(typeArg.type()), factory);
      }
    }
    */
    
    if (Map.class.isAssignableFrom(cl)) {
      return new MapJavaSerializer(typeImpl, this, cl);
    }
    else if (Collection.class.isAssignableFrom(cl)) {
      return new ListJavaSerializer(typeImpl, this, cl);
    }
    
    /*
    if (cl.isInterface() && Map.class.isAssignableFrom(cl)) {
      TypeRef mapType = typeImpl.to(Map.class);
      TypeRef keyType = mapType.param(0);
      TypeRef valueType = mapType.param(1);
      
      Supplier<Map<Object,Object>> factory = (Supplier) _factoryMap.get(cl);

      if (factory != null) {
        return new MapDeserializer(getDeserializer(keyType.type()),
                                   getDeserializer(valueType.type()),
                                   factory);
      }
    }
    */
    
    if (cl.isArray()) {
      Class<?> compType = cl.getComponentType();
      Class<?> eltType = cl.getComponentType();
      /*
      typeImpl.getArg(0, _typeFactory);
      Class<?> eltType = typeImpl.getTypeClass().getComponentType();
      */

      if (compType == null) {
        compType = eltType;
      }

      JsonDeserializer compDeser = deserializer(compType);

      deser = new ObjectArrayDeserializer(eltType, compDeser);

      return deser;
    }
    
    JavaDeserializer javaDeser = new JavaDeserializer(typeImpl);

    // early put for circular
    _deserMap.putIfAbsent(type, javaDeser);

    javaDeser.introspect(this);

    return javaDeser;
  }
  
  protected Method findWriteReplace(Class<?> cl)
  {
    if (cl == null) {
      return null;
    }
    
    for (Method method : cl.getDeclaredMethods()) {
      if (method.getName().equals("writeReplace")
          && method.getParameterTypes().length == 0) {
                return method;
      }
    }
    
    return findWriteReplace(cl.getSuperclass());
  }

  protected JsonDeserializer createDeserializerGeneric(Type type)
  {
    throw new UnsupportedOperationException(getClass().getName());
  }
  
  //
  // i/o streams
  //

  public JsonWriter out(Writer os)
  {
    JsonWriter out = new JsonWriter(this);
    
    out.init(os);
    
    return out;
  }

  public JsonReader in(StringReader is)
  {
    JsonReader in = new JsonReader(is, this);
    
    return in;
  }
  
  private class SerializerClassValue extends ClassValue<JsonSerializer<?>>
  {
    @Override
    protected JsonSerializer<?> computeValue(Class<?> type)
    {
      return createSerializer(type);
    }
  }
  
  interface CollectionFunction
  {
    JsonSerializerBase<?> apply(TypeRef type, JsonSerializerFactory factory);
  }

  static {
    _staticSerMap.put(boolean.class, BooleanSerializer.SER);
    _staticSerMap.put(Boolean.class, BooleanSerializer.SER);

    _staticSerMap.put(char.class, CharSerializer.SER);
    _staticSerMap.put(Character.class, CharSerializer.SER);

    _staticSerMap.put(byte.class, LongSerializer.SER);
    _staticSerMap.put(Byte.class, LongSerializer.SER);

    _staticSerMap.put(short.class, LongSerializer.SER);
    _staticSerMap.put(Short.class, LongSerializer.SER);

    _staticSerMap.put(int.class, LongSerializer.SER);
    _staticSerMap.put(Integer.class, LongSerializer.SER);

    _staticSerMap.put(long.class, LongSerializer.SER);
    _staticSerMap.put(Long.class, LongSerializer.SER);

    _staticSerMap.put(float.class, DoubleSerializer.SER);
    _staticSerMap.put(Float.class, DoubleSerializer.SER);

    _staticSerMap.put(double.class, DoubleSerializer.SER);
    _staticSerMap.put(Double.class, DoubleSerializer.SER);

    _staticSerMap.put(String.class, StringSerializer.SER);

    _staticSerMap.put(boolean[].class, BooleanArraySerializer.SER);
    _staticSerMap.put(byte[].class, ByteArraySerializer.SER);
    _staticSerMap.put(char[].class, CharArraySerializer.SER);
    _staticSerMap.put(short[].class, ShortArraySerializer.SER);
    _staticSerMap.put(int[].class, IntArraySerializer.SER);
    _staticSerMap.put(long[].class, LongArraySerializer.SER);
    _staticSerMap.put(float[].class, FloatArraySerializer.SER);
    _staticSerMap.put(double[].class, DoubleArraySerializer.SER);
    
    _staticSerMap.put(AtomicInteger.class, AtomicIntegerSerializer.SER);
    _staticSerMap.put(Date.class, DateSerializer.SER);
    _staticSerMap.put(UUID.class, UuidSerializer.SER);

    _staticSerMap.put(ZonedDateTime.class, ZonedDateTimeSerializer.SER);

    /*
     * Deserializers
     */

    _staticDeserMap.put(boolean.class, BooleanSerializer.SER);
    _staticDeserMap.put(Boolean.class, BooleanSerializer.SER);

    _staticDeserMap.put(char.class, CharSerializer.SER);
    _staticDeserMap.put(Character.class, CharSerializer.SER);

    _staticDeserMap.put(byte.class, ByteSerializer.SER);
    _staticDeserMap.put(Byte.class, ByteSerializer.SER);

    _staticDeserMap.put(short.class, ShortSerializer.SER);
    _staticDeserMap.put(Short.class, ShortSerializer.SER);

    _staticDeserMap.put(int.class, IntSerializer.SER);
    _staticDeserMap.put(Integer.class, IntSerializer.SER);

    _staticDeserMap.put(long.class, LongSerializer.SER);
    _staticDeserMap.put(Long.class, LongSerializer.SER);

    _staticDeserMap.put(float.class, FloatSerializer.DESER);
    _staticDeserMap.put(Float.class, FloatSerializer.DESER);

    _staticDeserMap.put(double.class, DoubleSerializer.SER);
    _staticDeserMap.put(Double.class, DoubleSerializer.SER);

    _staticDeserMap.put(String.class, StringSerializer.SER);
    _staticDeserMap.put(Object.class, ObjectDeserializer.DESER);

    _staticDeserMap.put(boolean[].class, BooleanArraySerializer.SER);
    _staticDeserMap.put(byte[].class, ByteArraySerializer.SER);
    _staticDeserMap.put(short[].class, ShortArraySerializer.SER);
    _staticDeserMap.put(int[].class, IntArraySerializer.SER);
    _staticDeserMap.put(long[].class, LongArraySerializer.SER);
    _staticDeserMap.put(float[].class, FloatArraySerializer.SER);
    _staticDeserMap.put(double[].class, DoubleArraySerializer.SER);

    _staticDeserMap.put(JsonValue.class, JsonValueDeserializer.DESER);
    _staticDeserMap.put(Date.class, DateSerializer.SER);
    _staticDeserMap.put(UUID.class, UuidSerializer.SER);

    _staticDeserMap.put(ZonedDateTime.class, ZonedDateTimeSerializer.SER);
    
    _factoryMap.put(Map.class, HashMapSerializer::new);
    _factoryMap.put(SortedMap.class, TreeMapSerializer::new);
    _factoryMap.put(NavigableMap.class, TreeMapSerializer::new);
    
    _factoryMap.put(Collection.class, ArrayListDeserializer::new);
    _factoryMap.put(List.class, ArrayListDeserializer::new);
    _factoryMap.put(Queue.class, DequeDeserializer::new);
    _factoryMap.put(Deque.class, DequeDeserializer::new);
    _factoryMap.put(Enumeration.class, EnumerationDeserializer::new);
    _factoryMap.put(Iterable.class, IterableDeserializer::new);
    _factoryMap.put(Stream.class, StreamDeserializer::new);
    _factoryMap.put(Set.class, HashSetDeserializer::new);
    _factoryMap.put(SortedSet.class, TreeSetDeserializer::new);
    _factoryMap.put(NavigableSet.class, TreeSetDeserializer::new);
  }
}