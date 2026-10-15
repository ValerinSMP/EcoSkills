package com.willfp.ecoskills.build;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.util.*;
import java.util.jar.*;
import java.nio.file.*;
import java.security.*;
/** Compose on the EXACT audited runtime. All unspecified entries/methods are retained, not rebuilt. */
public final class ComposeDurableRuntime implements Opcodes {
 static Map<String,byte[]> read(Path file)throws Exception {var map=new LinkedHashMap<String,byte[]>();try(var jar=new JarFile(file.toFile())){var entries=jar.entries();while(entries.hasMoreElements()){var e=entries.nextElement();try(var in=jar.getInputStream(e)){map.put(e.getName(),in.readAllBytes());}}}return map;}
 static ClassNode node(byte[] b){var n=new ClassNode();new ClassReader(b).accept(n,0);return n;}
 static byte[] bytes(ClassNode n){var w=new ClassWriter(ClassWriter.COMPUTE_MAXS);n.accept(w);return w.toByteArray();}
 static Map<String,Set<String>> edited=new HashMap<>();
 static Set<String> fieldsAdded=new HashSet<>();
 static void merge(Map<String,byte[]> old,Map<String,byte[]> fresh,String path,Set<String> methods,Set<String> newFields) {
  var a=node(Objects.requireNonNull(old.get(path),path));var b=node(Objects.requireNonNull(fresh.get(path),path));
  for(var f:b.fields)if(newFields.contains(f.name)&&a.fields.stream().noneMatch(x->x.name.equals(f.name))){a.fields.add(f);fieldsAdded.add(path+":"+f.name);}
  var found=new HashSet<String>();
  for(var m:b.methods)if(methods.contains(m.name)||methods.stream().anyMatch(x->x.endsWith("*")&&m.name.startsWith(x.substring(0,x.length()-1)))) {
   a.methods.removeIf(x->x.name.equals(m.name)&&x.desc.equals(m.desc));a.methods.add(m);found.add(m.name);
  }
  for(String name:methods)if(!name.endsWith("*")&&!found.contains(name))throw new IllegalStateException("Missing source method "+path+" "+name);
  old.put(path,bytes(a));edited.computeIfAbsent(path,k->new HashSet<>()).addAll(found);
 }
 static void prefix(Map<String,byte[]> old,Map<String,byte[]> fresh,String prefix){int added=0;for(var e:fresh.entrySet())if(e.getKey().startsWith(prefix)&&e.getKey().endsWith(".class")){old.put(e.getKey(),e.getValue());edited.computeIfAbsent(e.getKey(),k->new HashSet<>()).add("*");added++;}if(added==0)throw new IllegalStateException("Empty prefix "+prefix);}
 static void hook(Map<String,byte[]> old,Map<String,byte[]> fresh,String method,String target,boolean argument) {
  String path="com/willfp/ecoskills/EcoSkillsPlugin.class";var a=node(old.get(path));
  var m=a.methods.stream().filter(x->x.name.equals(method)&&x.desc.equals("()V")).findFirst().orElse(null);
  if(m==null){merge(old,fresh,path,Set.of(method),Set.of());return;}
  var code=new InsnList();if(argument)code.add(new VarInsnNode(ALOAD,0));code.add(new MethodInsnNode(INVOKESTATIC,"com/willfp/ecoskills/enchanting/EnchantingRuntime",target,argument?"(Lcom/willfp/ecoskills/EcoSkillsPlugin;)V":"()V",false));m.instructions.insert(code);
  old.put(path,bytes(a));edited.computeIfAbsent(path,k->new HashSet<>()).add(method);
 }
 static byte[] normalized(MethodNode method){var w=new ClassWriter(0);w.visit(V21,ACC_PUBLIC,"Comparison",null,"java/lang/Object",null);method.accept(w);return w.toByteArray();}
 static void verify(Map<String,byte[]> before,Map<String,byte[]> after)throws Exception {
  int identical=0,protectedMethods=0;
  for(var e:before.entrySet()) {
   var value=after.get(e.getKey());if(value==null)throw new AssertionError("Removed runtime entry "+e.getKey());
   if(Arrays.equals(e.getValue(),value)){identical++;continue;}
   if(!e.getKey().endsWith(".class")){if(!e.getKey().equals("config.yml"))throw new AssertionError("Changed resource "+e.getKey());continue;}
   var allowed=edited.get(e.getKey());if(allowed==null)throw new AssertionError("Unapproved runtime change "+e.getKey());
   var a=node(e.getValue());var b=node(value);
   if(a.access!=b.access||!Objects.equals(a.superName,b.superName)||!a.interfaces.equals(b.interfaces))throw new AssertionError("Class hierarchy changed");
   for(var f:a.fields)if(b.fields.stream().noneMatch(x->x.name.equals(f.name)&&x.desc.equals(f.desc)&&x.access==f.access))throw new AssertionError("Removed/changed field "+f.name);
   for(var m:a.methods) {
    var current=b.methods.stream().filter(x->x.name.equals(m.name)&&x.desc.equals(m.desc)).findFirst().orElse(null);
    if((m.access&(ACC_PUBLIC|ACC_PROTECTED))!=0&&(current==null||current.access!=m.access))throw new AssertionError("Removed public ABI "+e.getKey()+" "+m.name+m.desc);
    if(!allowed.contains("*")&&!allowed.contains(m.name)){
     if(current==null||!Arrays.equals(normalized(m),normalized(current)))throw new AssertionError("Changed unapproved method "+e.getKey()+" "+m.name);protectedMethods++;
    }
   }
  }
  System.out.println("Preserved byte-identical entries="+identical+" preserved old method bodies="+protectedMethods+" explicit added fields="+fieldsAdded);
 }
 public static void main(String[] args)throws Exception {
  Path baseline=Path.of(args[1]),target=Path.of(args[3]),work=Path.of(args[4]);Files.createDirectories(work);
  String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(baseline)));if(!hash.equals(args[5]))throw new IllegalStateException("Baseline hash mismatch");
  var original=read(baseline);var out=new LinkedHashMap<>(original);var fresh=new HashMap<String,byte[]>();for(String jar:args[2].split(","))fresh.putAll(read(Path.of(jar)));
  String root="com/willfp/";
  switch(args[0]) {
   case "eco" -> {
    merge(out,fresh,root+"eco/core/Eco.class",Set.of("getProfileMutationAccess"),Set.of());
    merge(out,fresh,root+"eco/core/data/Profile.class",Set.of("publishCommittedValues"),Set.of());
    prefix(out,fresh,root+"eco/core/data/handlers/ProfileMutationAccess");prefix(out,fresh,root+"eco/core/data/handlers/ProfileWriteBarrier");prefix(out,fresh,root+"eco/core/data/handlers/PreparedProfileWrite");
    merge(out,fresh,root+"eco/core/data/handlers/PersistentDataHandler.class",Set.of("<init>","installProfileWriteBarrier","prepareWrite","writePrepared","awaitProfileWrites","write","lambda$prepareWrite$*","lambda$awaitProfileWrites$*","lambda$writePrepared$*"),Set.of("profileWriteBarrier","pendingWrites"));
    merge(out,fresh,root+"eco/internal/spigot/EcoImpl.class",Set.of("getProfileMutationAccess"),Set.of());
    prefix(out,fresh,root+"eco/internal/spigot/EcoImpl$getProfileMutationAccess$");
    merge(out,fresh,root+"eco/internal/spigot/data/profiles/ProfileHandler.class",Set.of("publishCommitted"),Set.of());
    merge(out,fresh,root+"eco/internal/spigot/data/profiles/impl/EcoProfile.class",Set.of("write","publishCommittedValues"),Set.of());
    prefix(out,fresh,root+"eco/internal/spigot/data/profiles/ProfileWriter$");
    String writer=root+"eco/internal/spigot/data/profiles/ProfileWriter.class";out.put(writer,Objects.requireNonNull(fresh.get(writer)));edited.put(writer,Set.of("*"));
    merge(out,fresh,root+"eco/internal/spigot/data/handlers/impl/MySQLPersistentDataHandler.class",Set.of("installNativeWriter"),Set.of());
   }
   case "skills" -> {
    prefix(out,fresh,root+"ecoskills/api/enchanting/");prefix(out,fresh,root+"ecoskills/enchanting/");
    var skill=node(fresh.get(root+"ecoskills/skills/Skill.class"));var additions=new HashSet<String>();for(var m:skill.methods)if(m.name.startsWith("durableRewardRules")||m.name.startsWith("notifyLevelUpEffects"))additions.add(m.name);
    if(additions.size()!=2)throw new IllegalStateException("Skill callback/reward projection ABI");merge(out,fresh,root+"ecoskills/skills/Skill.class",additions,Set.of());
    hook(out,fresh,"handleEnable","enable",true);hook(out,fresh,"handleReload","reload",false);hook(out,fresh,"handleDisable","disable",true);
    for(String n:List.of("com/willfp/ecoskills/skills/SkillLevelMap","com/willfp/ecoskills/util/LevelMap","com/willfp/ecoskills/api/EcoSkillsAPI")) {var file=work.resolve("guard-input").resolve(n+".class");Files.createDirectories(file.getParent());Files.write(file,out.get(n+".class"));}
    NativeOperationGuard.main(new String[]{work.resolve("guard-input").toString(),work.resolve("guard-output").toString()});
    for(String n:List.of("com/willfp/ecoskills/skills/SkillLevelMap","com/willfp/ecoskills/util/LevelMap","com/willfp/ecoskills/api/EcoSkillsAPI")) {out.put(n+".class",Files.readAllBytes(work.resolve("guard-output").resolve(n+".class")));edited.put(n+".class",n.endsWith("EcoSkillsAPI")?Set.of("giveBaseStatLevel"):Set.of("set","reset","giveXP","gainXP"));}
    String config=new String(out.get("config.yml"),java.nio.charset.StandardCharsets.UTF_8);config+="\nenchanting-purchase-xp:\n  enabled: false\n  scope: \"\"\n";out.put("config.yml",config.getBytes(java.nio.charset.StandardCharsets.UTF_8));
   }
   case "enchants" -> prefix(out,fresh,root+"ecoenchants/api/");
   default -> throw new IllegalArgumentException("Runtime mode");
  }
  verify(original,out);
  try(var jar=new JarOutputStream(Files.newOutputStream(target))){for(var entry:out.entrySet()){var info=new JarEntry(entry.getKey());info.setTime(0);jar.putNextEntry(info);jar.write(entry.getValue());jar.closeEntry();}}
  System.out.println(target+" SHA256="+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(target))));
 }
}
