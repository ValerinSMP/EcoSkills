package com.willfp.ecoskills.build;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.nio.file.*;
import java.util.*;
public final class VerifyRuntimeClosure {
 static Map<String,ClassNode> classes=new HashMap<>();
 static boolean ours(String owner){return owner.startsWith("com/willfp/")&&!owner.contains("/libs/")&&!owner.contains("/ecomponent/");}
 static boolean method(String owner,String name,String desc,Set<String> seen){
  if(!ours(owner)||!classes.containsKey(owner)||!seen.add(owner))return !ours(owner)||!classes.containsKey(owner);
  var c=classes.get(owner);if(c.methods.stream().anyMatch(m->m.name.equals(name)&&m.desc.equals(desc)))return true;
  if(name.equals("<init>")||name.startsWith("lambda$")||name.startsWith("access$"))return false;
  if(c.superName!=null&&method(c.superName,name,desc,seen))return true;
  for(String i:c.interfaces)if(method(i,name,desc,seen))return true;return false;
 }
 static boolean field(String owner,String name,String desc,Set<String> seen){
  if(!ours(owner)||!classes.containsKey(owner)||!seen.add(owner))return !ours(owner)||!classes.containsKey(owner);
  var c=classes.get(owner);if(c.fields.stream().anyMatch(f->f.name.equals(name)&&f.desc.equals(desc)))return true;
  if(c.superName!=null&&ours(c.superName)&&field(c.superName,name,desc,seen))return true;
  for(String i:c.interfaces)if(ours(i)&&field(i,name,desc,seen))return true;return false;
 }
 public static void main(String[] args)throws Exception{
  var changed=new HashSet<String>();
  for(int index=0;index<args.length;index+=2){var baseline=ComposeDurableRuntime.read(Path.of(args[index]));var after=ComposeDurableRuntime.read(Path.of(args[index+1]));
   for(var e:after.entrySet())if(e.getKey().endsWith(".class")&&ours(e.getKey())){var node=ComposeDurableRuntime.node(e.getValue());classes.put(node.name,node);if(!Arrays.equals(baseline.get(e.getKey()),e.getValue()))changed.add(node.name);}
  }
  int checks=0;
  for(String name:changed){var c=classes.get(name);if(!ours(name))continue;
   for(var m:c.methods)for(var instruction:m.instructions){
    if(instruction instanceof MethodInsnNode call&&ours(call.owner)&&classes.containsKey(call.owner)){
     if(!method(call.owner,call.name,call.desc,new HashSet<>()))throw new AssertionError("Missing method "+name+" -> "+call.owner+"."+call.name+call.desc);checks++;
    }
    if(instruction instanceof FieldInsnNode access&&ours(access.owner)&&classes.containsKey(access.owner)){
     if(!field(access.owner,access.name,access.desc,new HashSet<>()))throw new AssertionError("Missing field "+name+" -> "+access.owner+"."+access.name+access.desc);checks++;
    }
    if(instruction instanceof InvokeDynamicInsnNode dynamic)for(Object argument:dynamic.bsmArgs)if(argument instanceof Handle handle&&ours(handle.getOwner())&&classes.containsKey(handle.getOwner())){
     if(!method(handle.getOwner(),handle.getName(),handle.getDesc(),new HashSet<>()))throw new AssertionError("Missing lambda target "+handle);checks++;
    }
   }
  }
  System.out.println("Changed runtime classes="+changed.size()+" checked owned member references="+checks+"; external server/dependency execution not claimed");
 }
}
