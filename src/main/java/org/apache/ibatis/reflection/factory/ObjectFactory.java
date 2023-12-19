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
package org.apache.ibatis.reflection.factory;

import java.util.List;
import java.util.Properties;

/**
 * MyBatis uses an ObjectFactory to create all needed new Objects.
 *
 * @author Clinton Begin
 */
public interface ObjectFactory {

	/**
	 * Sets configuration properties.
	 *
	 * @param properties configuration properties
	 */
	default void setProperties(Properties properties) {
		// NOP
	}

	/**
	 * Creates a new object with default constructor.
	 * <p>
	 * 通过默认构造函数创建一个新的对象
	 *
	 * @param <T>  the generic type
	 * @param type Object type
	 * @return the t
	 */
	<T> T create(Class<T> type);

	/**
	 * Creates a new object with the specified constructor and params.
	 * <p>
	 * 通过指定的构造函数和参数创建一个新的对象
	 *
	 * @param <T>                 the generic type
	 * @param type                Object type
	 * @param constructorArgTypes Constructor argument types
	 * @param constructorArgs     Constructor argument values
	 * @return the t
	 */
	<T> T create(Class<T> type, List<Class<?>> constructorArgTypes, List<Object> constructorArgs);

	/**
	 * 如果此对象可以有一组其他对象，则返回true。它的主要目的是支持
	 * 非 java.util.collection对象，比如Scala集合。
	 * <p>
	 * 判断是否是类型
	 *
	 * @param <T>  the generic type
	 * @param type Object type
	 * @return whether it is a collection or not
	 * @since 3.1.0
	 */
	<T> boolean isCollection(Class<T> type);

}
