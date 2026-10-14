import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.util.*;
import java.util.jar.*;
import java.nio.file.*;
import java.io.*;
/** Append or explicitly replace approved methods, preserving every other runtime method. */
public class Graft {
 static Map<String,byte[]> read(String p)throws Exception {
  var map=new LinkedHashMap<String,byte[]>();try(var z=new JarFile(p)){var e=z.entries();while(e.hasMoreElements()){var n=e.nextElement();map.put(n.getName(),z.getInputStream(n).readAllBytes());}}return map;
 }
 static byte[] merge(byte[] old,byte[] fresh,Set<String> names,boolean replace)throws Exception {
  var a=new ClassNode();var b=new ClassNode();new ClassReader(old).accept(a,0);new ClassReader(fresh).accept(b,0);
  for(var m:b.methods)if(names.contains(m.name)) {
   var existing=a.methods.stream().filter(x->x.name.equals(m.name)&&x.desc.equals(m.desc)).findFirst();
   if(existing.isPresent()){if(!replace)throw new IllegalStateException("Already exists: "+m.name);a.methods.remove(existing.get());}
   a.methods.add(m);
  }
  // Kotlin metadata reflects the source's new public additions; all pre-existing code is retained.
  if(a.visibleAnnotations!=null&&b.visibleAnnotations!=null) {
   a.visibleAnnotations.removeIf(x->(x.desc.equals("Lkotlin/Metadata;")||x.desc.equals("Lcom/willfp/eco/libs/kotlin/Metadata;")));
   b.visibleAnnotations.stream().filter(x->(x.desc.equals("Lkotlin/Metadata;")||x.desc.equals("Lcom/willfp/eco/libs/kotlin/Metadata;"))).forEach(a.visibleAnnotations::add);
  }
  for(String name:names)if(b.methods.stream().noneMatch(m->m.name.equals(name)))throw new IllegalStateException("Missing approved method: "+name);
  var w=new ClassWriter(0);a.accept(w);return w.toByteArray();
 }
 public static void main(String[] args)throws Exception {
  var old=read(args[0]);var fresh=new HashMap<String,byte[]>();
  for(String p:args[3].split(","))fresh.putAll(read(p));
  var spec=Files.readAllLines(Path.of(args[2]));
  for(String line:spec) {
   if(line.isBlank())continue;var parts=line.split(" ",3);String n=parts[1];
   if(parts[0].equals("PREFIX")){for(var e:fresh.entrySet())if(e.getKey().startsWith(n)&&e.getKey().endsWith(".class"))old.put(e.getKey(),e.getValue());}
   else {var names=new HashSet<>(Arrays.asList(parts[2].split(",")));old.put(n,merge(old.get(n),Objects.requireNonNull(fresh.get(n),n),names,parts[0].equals("REPLACE")));}
  }
  try(var out=new JarOutputStream(Files.newOutputStream(Path.of(args[1])))){for(var e:old.entrySet()){out.putNextEntry(new JarEntry(e.getKey()));out.write(e.getValue());out.closeEntry();}}
 }
}
