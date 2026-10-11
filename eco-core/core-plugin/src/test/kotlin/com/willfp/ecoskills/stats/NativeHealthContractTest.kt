package com.willfp.ecoskills.stats

import net.bytebuddy.jar.asm.*
import org.bukkit.entity.LivingEntity
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito

/** Executes the native health clamp bytecode without bootstrapping Bukkit registries. */
class NativeHealthContractTest {
    private fun bytecode(resource: String): ByteArray {
        val runtime = System.getProperty("libreforge.runtime.jar", "")
        return if (runtime.isNotEmpty()) java.util.zip.ZipFile(runtime).use { jar ->
            jar.getInputStream(jar.getEntry(resource)).use { it.readBytes() }
        } else javaClass.classLoader.getResourceAsStream(resource)!!.use { it.readBytes() }
    }
    private fun nativeHealthClamp(): Any {
        val writer = ClassWriter(0)
        val name = "com/willfp/ecoskills/stats/NativeHealthClampFixture"
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)
        writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode(); visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN); visitMaxs(1, 1); visitEnd()
        }
        val resource = "com/willfp/libreforge/effects/impl/EffectBonusHealth.class"
        val bytes = bytecode(resource)
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(access: Int, method: String, descriptor: String,
                                     signature: String?, exceptions: Array<out String>?): MethodVisitor? =
                if (method == "constrainAttribute") object : MethodVisitor(Opcodes.ASM9, writer.visitMethod(access, method, descriptor, signature, exceptions)) {
                    override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
                        // Runtime shades Kotlin; only relocate its argument check for this JVM fixture.
                        super.visitMethodInsn(opcode, if (owner.endsWith("/Intrinsics")) "kotlin/jvm/internal/Intrinsics" else owner, name, descriptor, isInterface)
                    }
                } else null
        }, 0)
        writer.visitEnd()
        val loader = object : ClassLoader(javaClass.classLoader) {
            fun fixture() = defineClass(name.replace('/', '.'), writer.toByteArray(), 0, writer.toByteArray().size)
        }
        return loader.fixture().getConstructor().newInstance()
    }

    @Test fun `native health removal never heals below the maximum and clamps excess only`() {
        val fixture = nativeHealthClamp()
        val clamp = fixture.javaClass.getMethod("constrainAttribute", LivingEntity::class.java, Double::class.javaPrimitiveType)
        val entity = Mockito.mock(LivingEntity::class.java)
        Mockito.`when`(entity.health).thenReturn(10.0)
        clamp.invoke(fixture, entity, 20.02)
        Mockito.verify(entity, Mockito.never()).health = Mockito.anyDouble()
        Mockito.`when`(entity.health).thenReturn(20.02)
        clamp.invoke(fixture, entity, 20.0)
        Mockito.verify(entity).health = 20.0
    }

    @Test fun `native enabling adds an attribute without setting health and disabling removes it`() {
        val calls = mutableMapOf<String, MutableList<String>>()
        val bytes = bytecode("com/willfp/libreforge/effects/templates/AttributeEffect.class")
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(access: Int, name: String, descriptor: String,
                                     signature: String?, exceptions: Array<out String>?) = object : MethodVisitor(Opcodes.ASM9) {
                override fun visitMethodInsn(opcode: Int, owner: String, method: String, descriptor: String, isInterface: Boolean) {
                    calls.getOrPut(name) { mutableListOf() }.add(method)
                }
            }
        }, 0)
        assertTrue(calls.getValue("onEnable").contains("addModifier"))
        assertFalse(calls.getValue("onEnable").contains("setHealth"))
        assertTrue(calls.getValue("onDisable").contains("removeModifier"))
        assertFalse(calls.getValue("onDisable").contains("addModifier"))
    }
}
