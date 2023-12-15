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
package org.apache.ibatis.plugin;

import org.apache.ibatis.reflection.ExceptionUtil;
import org.apache.ibatis.util.MapUtil;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * @author Clinton Begin
 * 该类的作用主要是通过jdk动态代理实现拦截器的拦截功能；且拦截器只能拦截接口内定义的方法；
 * 当想要使用mybatis的拦截器时，需要一下两步：
 * 1. 实现Interceptor接口，并在实现类上添加@Interceptors注解，在注解内使用@Signature类指定需要拦截目标对象和方法；
 * 例如：
 * <code>
 * &#064; @Interceptors({&#064; @Signature(type=Test.class, method="test", args={String.class})})
 * public class MyInterceptor implements Interceptor {
 * Object intercept(Invocation invocation) throws Throwable{
 * // TODO 处理被拦截方法test逻辑
 * return invocation.proceed();
 * }
 * }
 * </code>
 * 2. 定义被拦截对象TestImpl
 * public class TestImpl implements Test {
 * &#064;@override
 * public void test(String arg){
 * // 正常业务逻辑
 * }
 * }
 * 3. 将拦截器通过配置文件注册
 * <plugin interceptor="org.apache.ibatis.builder.MyInterceptor">
 * <property name="pluginProperty" value="100"/>
 * </plugin>
 * mybatis会自动帮我们注册几个被拦截的对象
 */
public class Plugin implements InvocationHandler {

	private final Object target;
	private final Interceptor interceptor;
	private final Map<Class<?>, Set<Method>> signatureMap;

	private Plugin(Object target, Interceptor interceptor, Map<Class<?>, Set<Method>> signatureMap) {
		this.target = target;
		this.interceptor = interceptor;
		this.signatureMap = signatureMap;
	}

	/**
	 * @param target:      目标对象
	 * @param interceptor: 拦截器对象
	 * @Author zpp
	 * @Description 包装target对象，返回target对象的代理类，该代理类会实现target对象中所有接口的方法
	 * @Date 2023/12/15 10:03
	 * @Return java.lang.Object
	 */
	public static Object wrap(Object target, Interceptor interceptor) {
		// 读取拦截器对象上@Interceptors注解属性@Signature标注的类对象和方法，可能会返回一个数量为0的map
		// 简单说就是读取需要拦截的方法
		Map<Class<?>, Set<Method>> signatureMap = getSignatureMap(interceptor);
		Class<?> type = target.getClass();
		// 读取target目标类的所有接口，如果接口在被拦截范围则返回，使用signatureMap判断是否包含
		Class<?>[] interfaces = getAllInterfaces(type, signatureMap);
		if (interfaces.length > 0) {
			// 返回target对象的代理类，该代理类会实现target对象中所有接口的方法
			return Proxy.newProxyInstance(type.getClassLoader(), interfaces, new Plugin(target, interceptor, signatureMap));
		}
		return target;
	}

	/**
	 * 通过jdk动态代理实现拦截器
	 */
	@Override
	public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
		try {
			Set<Method> methods = signatureMap.get(method.getDeclaringClass());
			if (methods != null && methods.contains(method)) {
				return interceptor.intercept(new Invocation(target, method, args));
			}
			return method.invoke(target, args);
		} catch (Exception e) {
			throw ExceptionUtil.unwrapThrowable(e);
		}
	}

	/**
	 * @param interceptor:
	 * @Author zpp
	 * @Description 该方法会读取拦截器上标注的@Intercepts注解，@Intercepts注解的属性是@Signature数组
	 * 其中@Signature注解有三个属性，主要作用是读取class类的方法，起作用是用来拦截@Signature标注的类和方法
	 * @Date 2023/12/15 9:49
	 * @Return java.util.Map<java.lang.Class < ?>,java.util.Set<java.lang.reflect.Method>>
	 */
	private static Map<Class<?>, Set<Method>> getSignatureMap(Interceptor interceptor) {
		Intercepts interceptsAnnotation = interceptor.getClass().getAnnotation(Intercepts.class);
		// issue #251
		// 拦截器必须提供@Intercepts注解
		if (interceptsAnnotation == null) {
			throw new PluginException("No @Intercepts annotation was found in interceptor " + interceptor.getClass().getName());
		}
		// 签名注解 @Signature
		Signature[] sigs = interceptsAnnotation.value();
		Map<Class<?>, Set<Method>> signatureMap = new HashMap<>();
		for (Signature sig : sigs) {
			Set<Method> methods = MapUtil.computeIfAbsent(signatureMap, sig.type(), k -> new HashSet<>());
			try {
				Method method = sig.type().getMethod(sig.method(), sig.args());
				methods.add(method);
			} catch (NoSuchMethodException e) {
				throw new PluginException("Could not find method on " + sig.type() + " named " + sig.method() + ". Cause: " + e,
						e);
			}
		}
		return signatureMap;
	}

	// 读取target目标类的所有接口，如果接口在被拦截范围则返回，使用signatureMap判断是否包含
	private static Class<?>[] getAllInterfaces(Class<?> type, Map<Class<?>, Set<Method>> signatureMap) {
		Set<Class<?>> interfaces = new HashSet<>();
		while (type != null) {
			for (Class<?> c : type.getInterfaces()) {
				if (signatureMap.containsKey(c)) {
					interfaces.add(c);
				}
			}
			type = type.getSuperclass();
		}
		return interfaces.toArray(new Class<?>[ 0 ]);
	}

}
