import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.util.*;
import java.util.jar.*;
import java.nio.file.*;
public class VerifyRuntime {
 static Map<String,byte[]> read(String path)throws Exception{var out=new TreeMap<String,byte[]>();try(var jar=new JarFile(path)){var entries=jar.entries();while(entries.hasMoreElements()){var e=entries.nextElement();out.put(e.getName(),jar.getInputStream(e).readAllBytes());}}return out;}
 static ClassNode node(byte[] value){var node=new ClassNode();new ClassReader(value).accept(node,ClassReader.SKIP_DEBUG);return node;}
 static byte[] method(MethodNode method){var out=new ClassWriter(0);out.visit(Opcodes.V21,Opcodes.ACC_PUBLIC,"Comparison",null,"java/lang/Object",null);method.accept(out);return out.toByteArray();}
 public static void main(String[] args)throws Exception{
  var before=read(args[0]);var after=read(args[1]);var allowed=new HashMap<String,Set<String>>();var replace=new HashMap<String,Set<String>>();var prefixes=new ArrayList<String>();
  for(String line:Files.readAllLines(Path.of(args[2]))){if(line.isBlank())continue;var parts=line.split(" ",3);if(parts[0].equals("PREFIX"))prefixes.add(parts[1]);else{allowed.put(parts[1],new HashSet<>(Arrays.asList(parts[2].split(","))));if(parts[0].equals("REPLACE"))replace.put(parts[1],allowed.get(parts[1]));}}
  int methods=0,unchanged=0;
  for(var e:before.entrySet()){
   var changed=after.get(e.getKey());if(changed==null)throw new AssertionError("Removed entry "+e.getKey());
   if(Arrays.equals(e.getValue(),changed)){unchanged++;continue;}
   if(!allowed.containsKey(e.getKey()))throw new AssertionError("Unapproved change "+e.getKey());
   var a=node(e.getValue());var b=node(changed);
   if(a.access!=b.access||!a.interfaces.equals(b.interfaces)||!Objects.equals(a.superName,b.superName))throw new AssertionError("Changed hierarchy");
   if(a.fields.size()!=b.fields.size())throw new AssertionError("Changed fields");
   for(int i=0;i<a.fields.size();i++){var x=a.fields.get(i);var y=b.fields.get(i);if(!x.name.equals(y.name)||!x.desc.equals(y.desc)||x.access!=y.access)throw new AssertionError("Changed field");}
   for(var old:a.methods){var fresh=b.methods.stream().filter(x->x.name.equals(old.name)&&x.desc.equals(old.desc)).findFirst().orElseThrow();if(old.access!=fresh.access)throw new AssertionError("Changed ABI "+old.name);
    if(!replace.getOrDefault(e.getKey(),Set.of()).contains(old.name)&&!Arrays.equals(method(old),method(fresh)))throw new AssertionError("Changed old method "+e.getKey()+" "+old.name+old.desc);methods++;
   }
  }
  for(String name:after.keySet())if(!before.containsKey(name)&&prefixes.stream().noneMatch(name::startsWith))throw new AssertionError("Unexpected new entry "+name);
  System.out.println("Preserved entries="+unchanged+" checked old methods="+methods+" approved replacements="+replace);
 }
}
