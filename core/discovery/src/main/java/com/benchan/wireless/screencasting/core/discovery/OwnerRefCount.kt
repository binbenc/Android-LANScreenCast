package com.benchan.wireless.screencasting.core.discovery

/** 按持有者（引用相等）计数：同一持有者重复获取只算一次。纯逻辑，单测覆盖。 */
class OwnerRefCount {
    private val owners = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())

    /** @return 获取前是否没有任何持有者。 */
    @Synchronized
    fun acquire(owner: Any): Boolean {
        val wasEmpty = owners.isEmpty()
        owners.add(owner)
        return wasEmpty
    }

    /** @return 释放后是否已没有任何持有者（释放一个未持有的对象时，按当前是否为空返回）。 */
    @Synchronized
    fun release(owner: Any): Boolean {
        owners.remove(owner)
        return owners.isEmpty()
    }

    @get:Synchronized
    val count: Int get() = owners.size
}
