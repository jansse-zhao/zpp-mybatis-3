/*
 *    Copyright 2009-2023 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.reflection;

import org.apache.ibatis.reflection.invoker.*;
import org.apache.ibatis.reflection.property.PropertyNamer;
import org.apache.ibatis.util.MapUtil;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.*;
import java.text.MessageFormat;
import java.util.*;
import java.util.Map.Entry;

/**
 * This class represents a cached set of class definition information that allows for easy mapping between property
 * names and getter/setter methods.
 * <p>
 * 基础组件-反射器
 * 该对象主要用于解析指定类Class<?> type，并将解析出来的属性存入反射器实例中
 *
 * @author Clinton Begin
 */
public class Reflector {

	/**
	 * MethodHandle方法句柄，用于检查对应类<type>中是否是record修饰的类
	 * record类是jdk14后才出现的新的定义类的关键字
	 */
	private static final MethodHandle isRecordMethodHandle = getIsRecordMethodHandle();

	/**
	 * 构造器对象持有的指定类对象
	 */
	private final Class<?> type;
	// get方法对应的属性名数组
	private final String[] readablePropertyNames;
	// set方法对应属性名数组
	private final String[] writablePropertyNames;
	// set方法对应的字段名称和SetFieldInvoker对象的k-v键值对
	private final Map<String, Invoker> setMethods = new HashMap<>();
	// get方法对应的字段名称和SetFieldInvoker对象的k-v键值对
	private final Map<String, Invoker> getMethods = new HashMap<>();
	// set方法对应的字段名称和字段类型的k-v键值对
	private final Map<String, Class<?>> setTypes = new HashMap<>();
	// get方法对应的字段名称和字段类型的k-v键值对
	private final Map<String, Class<?>> getTypes = new HashMap<>();
	/**
	 * 持有给定类type的默认构造函数
	 */
	private Constructor<?> defaultConstructor;

	/**
	 * 集合所有get和set方法解析出来的属性名放入同一个map中做去重，其中key是大写的属性名、value是小写的属性名
	 */
	private final Map<String, String> caseInsensitivePropertyMap = new HashMap<>();

	public Reflector(Class<?> clazz) {
		type = clazz;
		// 将clazz对象的默认构造函数赋值给defaultConstructor
		addDefaultConstructor(clazz);
		// 读取类所有的方法，包括所有父类和所有接口的方法，
		// 其中会过滤掉方法名、方法返回值类型、方法参数个数和类型相同的方法
		Method[] classMethods = getClassMethods(clazz);
		// 判断type类是否是record类，record类是jdk14后加入的
		if (isRecord(type)) {
			// 设置get方法k-v键值对
			addRecordGetMethods(classMethods);
		} else {
			// 遍历所有的methods，找到属于get的方法，将属性名和唯一一个get方法放入getMethods和getTypes中
			addGetMethods(classMethods);
			// 遍历所有的methods，找到属于set的方法，将属性名和唯一一个set方法放入setMethods和setTypes中
			addSetMethods(classMethods);
			// 递归设置type类和type类父类的非final和非static的set方法和get方法
			addFields(clazz);
		}
		// get方法对应的属性名数组
		readablePropertyNames = getMethods.keySet().toArray(new String[0]);
		// set方法对应属性名数组
		writablePropertyNames = setMethods.keySet().toArray(new String[0]);
		for (String propName : readablePropertyNames) {
			caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
		}
		for (String propName : writablePropertyNames) {
			caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
		}
	}

	// 过滤所有方法参数为0的方法，交给addGetMethod方法
	private void addRecordGetMethods(Method[] methods) {
		Arrays.stream(methods).filter(m -> m.getParameterTypes().length == 0)
				.forEach(m -> addGetMethod(m.getName(), m, false));
	}

	/**
	 * 获取clazz对象的无参构造起，并赋值给defaultConstructor
	 */
	private void addDefaultConstructor(Class<?> clazz) {
		Constructor<?>[] constructors = clazz.getDeclaredConstructors();
		Arrays.stream(constructors).filter(constructor -> constructor.getParameterTypes().length == 0).findAny()
				.ifPresent(constructor -> this.defaultConstructor = constructor);
	}

	private void addGetMethods(Method[] methods) {
		// 属性名对应的get方法集合
		Map<String, List<Method>> conflictingGetters = new HashMap<>();
		Arrays.stream(methods).filter(m -> m.getParameterTypes().length == 0 && PropertyNamer.isGetter(m.getName()))
				.forEach(m -> addMethodConflict(conflictingGetters, PropertyNamer.methodToProperty(m.getName()), m));
		resolveGetterConflicts(conflictingGetters);
	}

	/**
	 * 在List<Method>方法中找到一个get方法，放到getMethods和getTypes里面
	 */
	private void resolveGetterConflicts(Map<String, List<Method>> conflictingGetters) {
		for (Entry<String, List<Method>> entry : conflictingGetters.entrySet()) {
			Method winner = null;
			// 属性名
			String propName = entry.getKey();
			// 该字段用于标识是否存在定义不清楚的get方法，
			// 即出现多个方法名、返回值类型相同（且不是boolean类型）的get方法
			// 用于后续执行get方法时，抛出ReflectionException异常
			boolean isAmbiguous = false;
			// 循环的目的是找出get方法返回值，如果返回值存在父子关系则返回子类
			for (Method candidate : entry.getValue()) {
				// 当候胜出者为null时将第一个候选人当做胜出者
				if (winner == null) {
					winner = candidate;
					continue;
				}
				// 暂定胜出者的方法返回值类型
				Class<?> winnerType = winner.getReturnType();
				// 当前候选人的方法返回值类型
				Class<?> candidateType = candidate.getReturnType();
				// 判断属性的相邻两个get方法的返回值是否相等
				if (candidateType.equals(winnerType)) {
					// 返回值不是boolean类型直接返回
					if (!boolean.class.equals(candidateType)) {
						isAmbiguous = true;
						break;
					}
					// 是boolean类型，返回is开头的getter方法
					if (candidate.getName().startsWith("is")) {
						winner = candidate;
					}
				} else if (candidateType.isAssignableFrom(winnerType)) {
					// OK getter type is descendant
				} else if (winnerType.isAssignableFrom(candidateType)) {
					winner = candidate;
				} else {
					// 如果两个get方法的返回值类型不一样则返回数组第一个
					isAmbiguous = true;
					break;
				}
			}
			addGetMethod(propName, winner, isAmbiguous);
		}
	}

	private void addGetMethod(String name, Method method, boolean isAmbiguous) {
		MethodInvoker invoker = isAmbiguous ? new AmbiguousMethodInvoker(method, MessageFormat.format(
				"Illegal overloaded getter method with ambiguous type for property ''{0}'' in class ''{1}''. This breaks the JavaBeans specification and can cause unpredictable results.",
				name, method.getDeclaringClass().getName())) : new MethodInvoker(method);
		getMethods.put(name, invoker);
		// get方法返回值类型
		Type returnType = TypeParameterResolver.resolveReturnType(method, type);
		getTypes.put(name, typeToClass(returnType));
	}

	private void addSetMethods(Method[] methods) {
		Map<String, List<Method>> conflictingSetters = new HashMap<>();
		Arrays.stream(methods).filter(m -> m.getParameterTypes().length == 1 && PropertyNamer.isSetter(m.getName()))
				.forEach(m -> addMethodConflict(conflictingSetters, PropertyNamer.methodToProperty(m.getName()), m));
		resolveSetterConflicts(conflictingSetters);
	}

	private void addMethodConflict(Map<String, List<Method>> conflictingMethods, String name, Method method) {
		// 验证属性名不是以$开头并且不是“serialVersionUID”并且不是“class”
		if (isValidPropertyName(name)) {
			List<Method> list = MapUtil.computeIfAbsent(conflictingMethods, name, k -> new ArrayList<>());
			list.add(method);
		}
	}

	private void resolveSetterConflicts(Map<String, List<Method>> conflictingSetters) {
		for (Entry<String, List<Method>> entry : conflictingSetters.entrySet()) {
			String propName = entry.getKey();
			List<Method> setters = entry.getValue();
			Class<?> getterType = getTypes.get(propName);
			boolean isGetterAmbiguous = getMethods.get(propName) instanceof AmbiguousMethodInvoker;
			boolean isSetterAmbiguous = false;
			Method match = null;
			for (Method setter : setters) {
				if (!isGetterAmbiguous && setter.getParameterTypes()[0].equals(getterType)) {
					// should be the best match
					match = setter;
					break;
				}
				if (!isSetterAmbiguous) {
					match = pickBetterSetter(match, setter, propName);
					isSetterAmbiguous = match == null;
				}
			}
			if (match != null) {
				addSetMethod(propName, match);
			}
		}
	}

	private Method pickBetterSetter(Method setter1, Method setter2, String property) {
		if (setter1 == null) {
			return setter2;
		}
		Class<?> paramType1 = setter1.getParameterTypes()[0];
		Class<?> paramType2 = setter2.getParameterTypes()[0];
		if (paramType1.isAssignableFrom(paramType2)) {
			return setter2;
		}
		if (paramType2.isAssignableFrom(paramType1)) {
			return setter1;
		}
		MethodInvoker invoker = new AmbiguousMethodInvoker(setter1,
				MessageFormat.format(
						"Ambiguous setters defined for property ''{0}'' in class ''{1}'' with types ''{2}'' and ''{3}''.", property,
						setter2.getDeclaringClass().getName(), paramType1.getName(), paramType2.getName()));
		setMethods.put(property, invoker);
		Type[] paramTypes = TypeParameterResolver.resolveParamTypes(setter1, type);
		setTypes.put(property, typeToClass(paramTypes[0]));
		return null;
	}

	private void addSetMethod(String name, Method method) {
		MethodInvoker invoker = new MethodInvoker(method);
		setMethods.put(name, invoker);
		Type[] paramTypes = TypeParameterResolver.resolveParamTypes(method, type);
		setTypes.put(name, typeToClass(paramTypes[0]));
	}

	private Class<?> typeToClass(Type src) {
		Class<?> result = null;
		if (src instanceof Class) {
			result = (Class<?>) src;
		} else if (src instanceof ParameterizedType) {
			result = (Class<?>) ((ParameterizedType) src).getRawType();
		} else if (src instanceof GenericArrayType) {
			Type componentType = ((GenericArrayType) src).getGenericComponentType();
			if (componentType instanceof Class) {
				result = Array.newInstance((Class<?>) componentType, 0).getClass();
			} else {
				Class<?> componentClass = typeToClass(componentType);
				result = Array.newInstance(componentClass, 0).getClass();
			}
		}
		if (result == null) {
			result = Object.class;
		}
		return result;
	}

	private void addFields(Class<?> clazz) {
		Field[] fields = clazz.getDeclaredFields();
		for (Field field : fields) {
			if (!setMethods.containsKey(field.getName())) {
				// issue #379 - removed the check for final because JDK 1.5 allows
				// modification of final fields through reflection (JSR-133). (JGB)
				// pr #16 - final static can only be set by the classloader
				int modifiers = field.getModifiers();
				if ((!Modifier.isFinal(modifiers) || !Modifier.isStatic(modifiers))) {
					addSetField(field);
				}
			}
			if (!getMethods.containsKey(field.getName())) {
				addGetField(field);
			}
		}
		if (clazz.getSuperclass() != null) {
			addFields(clazz.getSuperclass());
		}
	}

	private void addSetField(Field field) {
		// 验证属性名不是以$开头并且不是“serialVersionUID”并且不是“class”
		if (isValidPropertyName(field.getName())) {
			setMethods.put(field.getName(), new SetFieldInvoker(field));
			Type fieldType = TypeParameterResolver.resolveFieldType(field, type);
			setTypes.put(field.getName(), typeToClass(fieldType));
		}
	}

	private void addGetField(Field field) {
		// 验证属性名不是以$开头并且不是“serialVersionUID”并且不是“class”
		if (isValidPropertyName(field.getName())) {
			getMethods.put(field.getName(), new GetFieldInvoker(field));
			Type fieldType = TypeParameterResolver.resolveFieldType(field, type);
			getTypes.put(field.getName(), typeToClass(fieldType));
		}
	}

	private boolean isValidPropertyName(String name) {
		return (!name.startsWith("$") && !"serialVersionUID".equals(name) && !"class".equals(name));
	}

	/**
	 * This method returns an array containing all methods declared in this class and any superclass. We use this method,
	 * instead of the simpler <code>Class.getMethods()</code>, because we want to look for private methods as well.
	 *
	 * @param clazz The class
	 * @return An array containing all methods in this class
	 * <p>
	 * 用于获取类直接定义的方法、类接口定义的方法、父类定义的方法数组
	 * 会过滤掉方法名、方法返回值类型、方法参数个数和返回值相同的方法
	 */
	private Method[] getClassMethods(Class<?> clazz) {
		// uniqueMethods过滤掉了方法名、方法返回值类型、方法参数个数和返回值相同的方法。
		Map<String, Method> uniqueMethods = new HashMap<>();
		Class<?> currentClass = clazz;
		// 过滤掉object对象的方法
		while (currentClass != null && currentClass != Object.class) {
			// 生成类所有方法的方法签名，并放入uniqueMethods
			addUniqueMethods(uniqueMethods, currentClass.getDeclaredMethods());

			// we also need to look for interface methods -
			// because the class may be abstract
			// 生成类实现接口方法的方法签名，并放入uniqueMethods
			Class<?>[] interfaces = currentClass.getInterfaces();
			for (Class<?> anInterface : interfaces) {
				addUniqueMethods(uniqueMethods, anInterface.getMethods());
			}

			currentClass = currentClass.getSuperclass();
		}

		Collection<Method> methods = uniqueMethods.values();

		return methods.toArray(new Method[0]);
	}

	private void addUniqueMethods(Map<String, Method> uniqueMethods, Method[] methods) {
		for (Method currentMethod : methods) {
			/**
			 * isBridge判断是否是桥接方法，此处只处理非桥接方法
			 *
			 * 桥接方法：桥接方法是 JDK1.5 引入泛型后，为了使泛型方法生成的字节码与之前版本的字节码兼容，由编译器自动生成的方法。
			 */
			if (!currentMethod.isBridge()) {
				// 生成方法签名
				String signature = getSignature(currentMethod);
				// check to see if the method is already known
				// if it is known, then an extended class must have
				// overridden a method
				// 将方法签名和对应的方法对象放入map
				if (!uniqueMethods.containsKey(signature)) {
					uniqueMethods.put(signature, currentMethod);
				}
			}
		}
	}

	/**
	 * 拼接方法签名，例如方法：public int cal(int a, int b){}
	 * 返回：int#cal:a,b
	 */
	private String getSignature(Method method) {
		StringBuilder sb = new StringBuilder();
		Class<?> returnType = method.getReturnType();
		sb.append(returnType.getName()).append('#');
		sb.append(method.getName());
		Class<?>[] parameters = method.getParameterTypes();
		for (int i = 0; i < parameters.length; i++) {
			sb.append(i == 0 ? ':' : ',').append(parameters[i].getName());
		}
		return sb.toString();
	}

	/**
	 * Checks whether can control member accessible.
	 *
	 * @return If can control member accessible, it return {@literal true}
	 * @since 3.5.0
	 */
	public static boolean canControlMemberAccessible() {
		try {
			SecurityManager securityManager = System.getSecurityManager();
			if (null != securityManager) {
				securityManager.checkPermission(new ReflectPermission("suppressAccessChecks"));
			}
		} catch (SecurityException e) {
			return false;
		}
		return true;
	}

	/**
	 * Gets the name of the class the instance provides information for.
	 *
	 * @return The class name
	 */
	public Class<?> getType() {
		return type;
	}

	public Constructor<?> getDefaultConstructor() {
		if (defaultConstructor != null) {
			return defaultConstructor;
		}
		throw new ReflectionException("There is no default constructor for " + type);
	}

	public boolean hasDefaultConstructor() {
		return defaultConstructor != null;
	}

	public Invoker getSetInvoker(String propertyName) {
		Invoker method = setMethods.get(propertyName);
		if (method == null) {
			throw new ReflectionException("There is no setter for property named '" + propertyName + "' in '" + type + "'");
		}
		return method;
	}

	public Invoker getGetInvoker(String propertyName) {
		Invoker method = getMethods.get(propertyName);
		if (method == null) {
			throw new ReflectionException("There is no getter for property named '" + propertyName + "' in '" + type + "'");
		}
		return method;
	}

	/**
	 * Gets the type for a property setter.
	 *
	 * @param propertyName - the name of the property
	 * @return The Class of the property setter
	 */
	public Class<?> getSetterType(String propertyName) {
		Class<?> clazz = setTypes.get(propertyName);
		if (clazz == null) {
			throw new ReflectionException("There is no setter for property named '" + propertyName + "' in '" + type + "'");
		}
		return clazz;
	}

	/**
	 * Gets the type for a property getter.
	 *
	 * @param propertyName - the name of the property
	 * @return The Class of the property getter
	 */
	public Class<?> getGetterType(String propertyName) {
		Class<?> clazz = getTypes.get(propertyName);
		if (clazz == null) {
			throw new ReflectionException("There is no getter for property named '" + propertyName + "' in '" + type + "'");
		}
		return clazz;
	}

	/**
	 * Gets an array of the readable properties for an object.
	 *
	 * @return The array
	 */
	public String[] getGetablePropertyNames() {
		return readablePropertyNames;
	}

	/**
	 * Gets an array of the writable properties for an object.
	 *
	 * @return The array
	 */
	public String[] getSetablePropertyNames() {
		return writablePropertyNames;
	}

	/**
	 * Check to see if a class has a writable property by name.
	 *
	 * @param propertyName - the name of the property to check
	 * @return True if the object has a writable property by the name
	 */
	public boolean hasSetter(String propertyName) {
		return setMethods.containsKey(propertyName);
	}

	/**
	 * Check to see if a class has a readable property by name.
	 *
	 * @param propertyName - the name of the property to check
	 * @return True if the object has a readable property by the name
	 */
	public boolean hasGetter(String propertyName) {
		return getMethods.containsKey(propertyName);
	}

	public String findPropertyName(String name) {
		return caseInsensitivePropertyMap.get(name.toUpperCase(Locale.ENGLISH));
	}

	/**
	 * Class.isRecord() alternative for Java 15 and older.
	 * <p>
	 * 判断一个类是否是Record类，Record类的属性不能被修改
	 * Class.isRecord
	 * <p>
	 * 在应用软件开发中，编程人员经常会针对底层数据，进行对数据的构造器、访问方法（getters）、覆盖方法equals、覆盖方法hashCode、以及覆盖方法toString进行基础和重复性的编程。
	 * 而使用Record类，程序中则可省去这些代码，而由支持Record的编译器自动生成。这不但提高了编程效率，而且提高了代码的可靠性。
	 * record是jdk14中出现的新的关键字，在14/15中以preview（预览）版出现，Record的出现一定程度上可以替代lombok库。（有待更深入的研究）
	 * 使用recode关键字定义一个类就是这么简单，不需要定义属性、get&set方法、toString方法、equals方法、hashCode方法：
	 * public record MyRecord(String name, Integer age) {}
	 * </p>
	 */
	private static boolean isRecord(Class<?> clazz) {
		try {
			return isRecordMethodHandle != null && (boolean) isRecordMethodHandle.invokeExact(clazz);
		} catch (Throwable e) {
			throw new ReflectionException("Failed to invoke 'Class.isRecord()'.", e);
		}
	}

	/**
	 * MethodHandle是1.7之后加入的，他主要用来定义一个方法的句柄，类似反射中的Method类
	 * 同样是执行目标方法，MethodHandle性能要比Method快很多
	 * <p>
	 * 关于MethodHandle参考：<a href="https://zhuanlan.zhihu.com/p/524591401">...</a>
	 * 1.为什么MethodHandle.invoke()不用进行方法修饰符权限检查？反射表示不服
	 * 2.为什么MethodHandle声明时要被final修饰，否则性能会大大打折扣？
	 * 3.为什么MethodHandle.invoke()明明是native方法为什么还可以被JIT内联优化？反射表示不服
	 * 4.MethodHandle.invoke()方法的调用栈链路是什么？
	 */
	private static MethodHandle getIsRecordMethodHandle() {
		// 1.创建MethodHandles.Lookup
		MethodHandles.Lookup lookup = MethodHandles.lookup();
		// 2.指定 返回值类型 和 参数类型 ，定义目标方法的MethodType；此处定义的是Class.isRecord方法的返回值类型
		MethodType mt = MethodType.methodType(boolean.class);
		try {
			// 3.通过MethodHandles.Lookup指定方法定义类、方法名称以及MethodType 来查找对应的方法句柄
			return lookup.findVirtual(Class.class, "isRecord", mt);
		} catch (NoSuchMethodException | IllegalAccessException e) {
			return null;
		}
	}
}
