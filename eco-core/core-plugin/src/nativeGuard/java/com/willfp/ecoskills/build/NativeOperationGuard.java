package com.willfp.ecoskills.build;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.nio.file.*;
import java.util.*;
/** Add only native entry admission guards, preserving existing bodies/foreign XP callbacks. */
public final class NativeOperationGuard implements Opcodes {
 private static final String QUEUE="com/willfp/ecoskills/enchanting/persistence/NativeOperationQueue";
 public static void main(String[] args)throws Exception {
  Path input=Path.of(args[0]),output=Path.of(args[1]);int guarded=0;
  for(String owner:List.of("com/willfp/ecoskills/skills/SkillLevelMap","com/willfp/ecoskills/util/LevelMap","com/willfp/ecoskills/api/EcoSkillsAPI")) {
   var node=new ClassNode();new ClassReader(Files.readAllBytes(input.resolve(owner+".class"))).accept(node,0);
   for(var method:node.methods) {
    boolean api=owner.endsWith("EcoSkillsAPI");
    if(!(api?method.name.equals("giveBaseStatLevel"):Set.of("set","giveXP","gainXP","reset").contains(method.name)))continue;
    if(Type.getReturnType(method.desc).getSort()!=Type.VOID)throw new IllegalStateException("Unexpected native descriptor");
    if(method.instructions.iterator().hasNext())for(var instruction:method.instructions)if(instruction instanceof MethodInsnNode call&&call.owner.equals(QUEUE))throw new IllegalStateException("Native class already guarded");
    InsnList code=new InsnList();LabelNode resume=new LabelNode();
    code.add(new MethodInsnNode(INVOKESTATIC,QUEUE,"active","()Z",false));code.add(new JumpInsnNode(IFEQ,resume));
    code.add(new VarInsnNode(ALOAD,0));
    if(!api)code.add(new FieldInsnNode(GETFIELD,owner,"player","Lorg/bukkit/OfflinePlayer;"));
    code.add(new MethodInsnNode(INVOKEINTERFACE,"org/bukkit/OfflinePlayer","getUniqueId","()Ljava/util/UUID;",true));
    if(api)code.add(new LdcInsnNode(Type.getObjectType(owner)));else code.add(new VarInsnNode(ALOAD,0));
    code.add(new LdcInsnNode(method.name));code.add(new LdcInsnNode(method.desc));
    var arguments=Type.getArgumentTypes(method.desc);code.add(new LdcInsnNode(arguments.length));code.add(new TypeInsnNode(ANEWARRAY,"java/lang/Object"));
    int local=api?0:1;
    for(int i=0;i<arguments.length;i++) {
     var argument=arguments[i];code.add(new InsnNode(DUP));code.add(new LdcInsnNode(i));code.add(new VarInsnNode(argument.getOpcode(ILOAD),local));local+=argument.getSize();
     if(argument.getSort()==Type.INT)code.add(new MethodInsnNode(INVOKESTATIC,"java/lang/Integer","valueOf","(I)Ljava/lang/Integer;",false));
     else if(argument.getSort()==Type.DOUBLE)code.add(new MethodInsnNode(INVOKESTATIC,"java/lang/Double","valueOf","(D)Ljava/lang/Double;",false));
     else if(argument.getSort()!=Type.OBJECT&&argument.getSort()!=Type.ARRAY)throw new IllegalStateException("Unexpected argument type");
     code.add(new InsnNode(AASTORE));
    }
    code.add(new MethodInsnNode(INVOKESTATIC,QUEUE,"deferInvocation","(Ljava/util/UUID;Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)Z",false));
    code.add(new JumpInsnNode(IFEQ,resume));code.add(new InsnNode(RETURN));code.add(resume);code.add(new FrameNode(F_SAME,0,null,0,null));
    method.instructions.insert(code);guarded++;
   }
   var writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(writer);var target=output.resolve(owner+".class");Files.createDirectories(target.getParent());Files.write(target,writer.toByteArray());
  }
  if(guarded<7||guarded>8)throw new IllegalStateException("Unexpected native entry point surface: "+guarded);
  System.out.println("Native complete-operation guards: "+guarded+"; original method bodies retained");
 }
}
