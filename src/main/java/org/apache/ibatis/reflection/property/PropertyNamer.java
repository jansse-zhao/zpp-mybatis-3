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
package org.apache.ibatis.reflection.property;

import org.apache.ibatis.reflection.ReflectionException;

import java.util.Locale;

/**
 * @author Clinton Begin
 * <p>
 * 此类是个工具类，比较简单，主要用于解析getter、setter方法
 * 1. getter、setter方法转属性名
 * 2. 判断方法是否是getter、setter方法
 */
public final class PropertyNamer {

	private PropertyNamer() {
		// Prevent Instantiation of Static Class
	}

	/**
	 * get/set方法转成首字母小写的属性名
	 */
	public static String methodToProperty(String name) {
		if (name.startsWith("is")) {
			name = name.substring(2);
		} else if (name.startsWith("get") || name.startsWith("set")) {
			name = name.substring(3);
		} else {
			throw new ReflectionException(
					"Error parsing property name '" + name + "'.  Didn't start with 'is', 'get' or 'set'.");
		}

		if (name.length() == 1 || name.length() > 1 && !Character.isUpperCase(name.charAt(1))) {
			name = name.substring(0, 1).toLowerCase(Locale.ENGLISH) + name.substring(1);
		}

		return name;
	}

	// 是否是类属性，即是否有getter或setter方法
	public static boolean isProperty(String name) {
		return isGetter(name) || isSetter(name);
	}

	public static boolean isGetter(String name) {
		return name.startsWith("get") && name.length() > 3 || name.startsWith("is") && name.length() > 2;
	}

	public static boolean isSetter(String name) {
		return name.startsWith("set") && name.length() > 3;
	}

}
