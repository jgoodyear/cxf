/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cxf.jaxrs.ext.search;

import java.beans.IntrospectionException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.apache.cxf.jaxrs.ext.search.collections.CollectionCheckInfo;

/**
 * Bean introspection utility.
 */
public class Beanspector<T> {
    private final Map< Class< ? >, Class< ? > > primitiveWrappers = getPrimitiveWrappers();

    private Class<T> tclass;
    private T tobj;
    private Map<String, Method> getters = new LinkedHashMap<>();
    private Map<String, Method> setters = new LinkedHashMap<>();

    public Beanspector(Class<T> tclass) {
        if (tclass == null) {
            throw new IllegalArgumentException("tclass is null");
        }
        this.tclass = tclass;
        init();
    }

    public Beanspector(T tobj) {
        if (tobj == null) {
            throw new IllegalArgumentException("tobj is null");
        }
        this.tobj = tobj;
        init();
    }

    @SuppressWarnings("unchecked")
    private void init() {
        if (tclass == null) {
            tclass = (Class<T>)tobj.getClass();
        }
        Map<String, List<Method>> detectGetters = new HashMap<>();
        Map<String, List<Method>> detectSetters = new HashMap<>();
        // process methods, build Getter/Setter maps
        detectMethods(tclass.getMethods(), detectGetters, detectSetters);
        // detect any pairings, regardless of method ordering.
        detectPairs(detectGetters, detectSetters);
        // check type equality for getter-setter pairs
        checkPairs();
    }

    private void detectMethods(Method[] methods,
                               Map<String, List<Method>> detectGetters,
                               Map<String, List<Method>> detectSetters) {
        for (Method m : methods) {
            if (isGetter(m)) {
                String pname = getPropertyName(m);
                if (!detectGetters.containsKey(pname)) {
                    List<Method> getMethods = new ArrayList<>();
                    getMethods.add(m);
                    detectGetters.put(pname, getMethods);
                } else {
                    List<Method> getMethods = detectGetters.get(pname);
                    getMethods.add(m);
                    detectGetters.put(pname, getMethods);
                }
            } else if (isSetter(m)) {
                String pname = getPropertyName(m);
                if (!detectSetters.containsKey(pname)) {
                    List<Method> setMethods = new ArrayList<>();
                    setMethods.add(m);
                    detectSetters.put(pname, setMethods);
                } else {
                    List<Method> setMethods = detectSetters.get(pname);
                    setMethods.add(m);
                    detectSetters.put(pname, setMethods);
                }
            }
        }
    }

    private void detectPairs(Map<String, List<Method>> detectGetters, Map<String, List<Method>> detectSetters) {

        Set<String> pairs = new HashSet<>(detectGetters.keySet());
        processDetectGetters(detectGetters);
        processDetectSetters(detectSetters);
        pairs.retainAll(detectSetters.keySet());
        for (String accessor : pairs) {
            List<Class<?>> getterClasses = detectGetters.get(accessor).stream()
                    .map(Method::getReturnType)
                    .collect(Collectors.toList());
            List<Class<?>> setterClasses = detectSetters.get(accessor).stream()
                    .map(method -> method.getParameterTypes()[0])
                    .collect(Collectors.toList());
            if (!Collections.disjoint(getterClasses, setterClasses)) {
                // Get the match(s), add first to getters / setters.
                getterClasses.retainAll(setterClasses);
                Method getterMethod = getMethodForGetterClass(getterClasses, detectGetters.get(accessor));
                Method setterMethod = getMethodForSetterClass(getterClasses, detectSetters.get(accessor));
                getters.put(accessor, getterMethod);
                setters.put(accessor, setterMethod);
            } else {
                throw new IllegalArgumentException(String
                        .format("Accessor '%s' type mismatch, getter types are %s while setter types are %s",
                        accessor,
                        getterClasses.stream().map(Class::getName).reduce("", String::concat),
                        setterClasses.stream().map(Class::getName).reduce("", String::concat)));
            }
        }
    }

    private void processDetectGetters(Map<String, List<Method>> detectGetters) {
        Set<String> keys = detectGetters.keySet();
        keys.forEach(key -> {
            detectGetters.get(key).forEach(method -> {
                getters.put(key, method);
            });
        });
    }

    private void processDetectSetters(Map<String, List<Method>> detectSetters) {
        Set<String> keys = detectSetters.keySet();
        keys.forEach(key -> {
            detectSetters.get(key).forEach(method -> {
                setters.put(key, method);
            });
        });
    }

    private Method getMethodForGetterClass(List<Class<?>> getterClasses, List<Method> detectGetters) {
        AtomicReference<Method> result = new AtomicReference<>();
        detectGetters.forEach(detectedGetter -> {
            Class<?> target = getterClasses.stream()
                    .filter(gc -> gc.getName().equals(detectedGetter.getReturnType().getName()))
                    .findAny()
                    .orElse(null);
            if (target != null) {
                result.set(detectedGetter);
            }
        });
        return result.get();
    }

    private Method getMethodForSetterClass(List<Class<?>> getterClasses, List<Method> detectSetters) {
        AtomicReference<Method> result = new AtomicReference<>();
        detectSetters.forEach(detectedSetter -> {
            Class<?> target = getterClasses.stream()
                    .filter(gc -> gc.getName().equals(detectedSetter.getParameterTypes()[0].getName()))
                    .findAny()
                    .orElse(null);
            if (target != null) {
                result.set(detectedSetter);
            }
        });
        return result.get();
    }

    private void checkPairs() {
        Set<String> pairs = new HashSet<>(getters.keySet());
        pairs.retainAll(setters.keySet());
        for (String accessor : pairs) {
            Class<?> getterClass = getters.get(accessor).getReturnType();
            Class<?> setterClass = setters.get(accessor).getParameterTypes()[0];
            if (!setterClass.isAssignableFrom(getterClass)) {
                throw new IllegalArgumentException(String
                        .format("Accessor '%s' type mismatch, getter type is %s while setter type is %s",
                                accessor, getterClass.getName(), setterClass.getName()));
            }
        }
    }

    public T getBean() {
        return tobj;
    }

    public Set<String> getGettersNames() {
        return Collections.unmodifiableSet(getters.keySet());
    }

    public Set<String> getSettersNames() {
        return Collections.unmodifiableSet(setters.keySet());
    }

    public TypeInfo getAccessorTypeInfo(String getterOrSetterName) throws Exception {
        Method m = getters.get(getterOrSetterName.toLowerCase());
        if (m == null) {
            m = setters.get(getterOrSetterName);
        }
        if (m == null) {
            String msg = String.format("Accessor '%s' not found, "
                                       + "known setters are: %s, known getters are: %s", getterOrSetterName,
                                       setters.keySet(), getters.keySet());
            throw new IntrospectionException(msg);
        }
        return new TypeInfo(m.getReturnType(), m.getGenericReturnType(),
            primitiveToWrapper(m.getReturnType()));
    }

    public Beanspector<T> swap(T newobject) throws Exception {
        if (newobject == null) {
            throw new IllegalArgumentException("newobject is null");
        }
        tobj = newobject;
        return this;
    }

    public Beanspector<T> instantiate() throws Exception {
        tobj = tclass.getDeclaredConstructor().newInstance();
        return this;
    }

    public Beanspector<T> setValue(String setterName, Object value) throws Throwable {
        Method m = setters.get(setterName.toLowerCase());
        if (m == null) {
            String msg = String.format("Setter '%s' not found, " + "known setters are: %s", setterName,
                                       setters.keySet());
            throw new IntrospectionException(msg);
        }
        setValue(m, value);
        return this;
    }

    public Beanspector<T> setValue(Map<String, Object> settersWithValues) throws Throwable {
        for (Map.Entry<String, Object> entry : settersWithValues.entrySet()) {
            setValue(entry.getKey(), entry.getValue());
        }
        return this;
    }

    public Beanspector<T> setValue(Method setter, Object value) throws Throwable {
        Class<?> paramType = setter.getParameterTypes()[0];
        try {
            setter.invoke(tobj, value);
            return this;
        } catch (InvocationTargetException e) {
            throw e.getCause();
        } catch (IllegalArgumentException e) {
            String msg = String.format("; setter parameter type: %s, set value type: %s",
                                       paramType.getName(), value.getClass().getName());
            throw new IllegalArgumentException(e.getMessage() + msg);
        } catch (Exception e) {
            throw e;
        }
    }

    public Object getValue(String getterName) throws Throwable {
        return getValue(getters.get(getterName));
    }

    public Object getValue(Method getter) throws Throwable {
        try {
            return getter.invoke(tobj);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        } catch (Exception e) {
            throw e;
        }
    }

    private Map< Class< ? >, Class< ? > > getPrimitiveWrappers() {
        final Map< Class< ? >, Class< ? > > wrappers = new HashMap<>();

        wrappers.put(boolean.class, Boolean.class);
        wrappers.put(byte.class, Byte.class);
        wrappers.put(char.class, Character.class);
        wrappers.put(short.class, Short.class);
        wrappers.put(int.class, Integer.class);
        wrappers.put(long.class, Long.class);
        wrappers.put(double.class, Double.class);
        wrappers.put(float.class, Float.class);

        return wrappers;
    }

    private Class< ? > primitiveToWrapper(final Class< ? > cls) {
        return cls.isPrimitive() ?  primitiveWrappers.get(cls) : cls;
    }

    private boolean isGetter(Method m) {
        return m.getParameterTypes().length == 0
               && (m.getName().startsWith("get") || m.getName().startsWith("is"));
    }

    private String getPropertyName(Method m) {
        // at this point the method is either getter or setter
        String result = m.getName().toLowerCase();

        if (result.startsWith("is")) {
            result = result.substring(2, result.length());
        } else {
            result = result.substring(3, result.length());
        }
        return result;

    }

    private boolean isSetter(Method m) {
        return (m.getReturnType().equals(void.class) || m.getReturnType().equals(m.getDeclaringClass()))
                && m.getParameterTypes().length == 1
                && (m.getName().startsWith("set") || m.getName().startsWith("is"));
    }


    public static class TypeInfo {
        private Class<?> cls;
        // The wrapper class in case cls is a primitive class (byte, long, ...)
        private Class<?> wrapper;
        private Type genericType;
        private CollectionCheckInfo checkInfo;

        public TypeInfo(Class<?> cls, Type genericType) {
            this(cls, genericType, cls);
        }

        public TypeInfo(Class<?> cls, Type genericType, Class<?> wrapper) {
            this.cls = cls;
            this.genericType = genericType;
            this.wrapper = wrapper;
        }

        public Class<?> getTypeClass() {
            return cls;
        }

        public Class<?> getWrappedTypeClass() {
            return wrapper;
        }

        public Type getGenericType() {
            return genericType;
        }

        public CollectionCheckInfo getCollectionCheckInfo() {
            return checkInfo;
        }

        public void setCollectionCheckInfo(CollectionCheckInfo info) {
            this.checkInfo = info;
        }
    }
}
