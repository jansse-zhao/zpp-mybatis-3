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

import org.apache.ibatis.util.MapUtil;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * @author zpp
 * @version 1.0.0
 * @ClassName DefaultReflectorFactory.java
 * @Description 反射器工厂只有两个实现类，你是其中一个，另一个继承了你，没啥用，没有具体实现
 * @createTime 23/12/16 23:05
 */

public class DefaultReflectorFactory implements ReflectorFactory {
	/**
	 * 默认开启类缓存
	 */
	private boolean classCacheEnabled = true;
	private final ConcurrentMap<Class<?>, Reflector> reflectorMap = new ConcurrentHashMap<>();

	public DefaultReflectorFactory() {
	}

	@Override
	public boolean isClassCacheEnabled() {
		return classCacheEnabled;
	}

	@Override
	public void setClassCacheEnabled(boolean classCacheEnabled) {
		this.classCacheEnabled = classCacheEnabled;
	}

	@Override
	public Reflector findForClass(Class<?> type) {
		if (classCacheEnabled) {
			// synchronized (type) removed see issue #461
			return MapUtil.computeIfAbsent(reflectorMap, type, Reflector::new);
		}
		return new Reflector(type);
	}

}
