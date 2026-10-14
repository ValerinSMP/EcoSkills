package com.willfp.ecoskills.api.observation;
import java.util.*;
import java.util.regex.*;
import java.lang.reflect.*;
/** Native parser, separate prepared expression; no PlaceholderAPI or global result/compile cache. */
public final class PureExpression {
 private final Object compiled;private final Method evaluate;private final List<String> variables;
 private PureExpression(Object compiled,Method evaluate,List<String> variables){this.compiled=compiled;this.evaluate=evaluate;this.variables=List.copyOf(variables);}
 public static PureExpression prepare(String expression,ClassLoader loader) {
  if(expression==null||expression.length()>512)return null;
  var names=new LinkedHashSet<String>();var m=Pattern.compile("%[a-z0-9_]+%").matcher(expression);
  while(m.find())names.add(m.group());
  String shape=expression;for(String name:names)shape=shape.replace(name,"1");
  var words=Pattern.compile("[A-Za-z_]+").matcher(shape);
  while(words.find())if(!Set.of("floor","ceil","round","sqrt","abs","ln","log","sin","cos","tan","min","max").contains(words.group()))return null;
  if(!shape.matches("[a-zA-Z0-9_ .+*/^(),\\-]+"))return null;
  try {
   Class<?> envClass=Class.forName("com.willfp.eco.internal.spigot.math.functional.ExpressionEnv",false,loader);
   Object env=envClass.getConstructor().newInstance();envClass.getMethod("setVariableNames",String[].class).invoke(env,(Object)names.toArray(String[]::new));
   Class<?> parser=Class.forName("com.willfp.eco.internal.spigot.math.ExpressionParser",false,loader);
   Object compiled=parser.getMethod("parse").invoke(parser.getConstructor(String.class,envClass).newInstance(expression,env));
   Method eval=Class.forName("com.willfp.eco.internal.spigot.math.CompiledExpression",false,loader).getMethod("evaluate",double[].class);
   return new PureExpression(compiled,eval,new ArrayList<>(names));
  }catch(ReflectiveOperationException|LinkageError e){return null;}
 }
 public List<String> variables(){return variables;}
 public synchronized Double evaluate(Map<String,Double> inputs) {
  double[] values=new double[variables.size()];
  for(int i=0;i<values.length;i++){Double v=inputs.get(variables.get(i));if(v==null||!Double.isFinite(v))return null;values[i]=v;}
  try {double value=((Number)evaluate.invoke(compiled,(Object)values)).doubleValue();return Double.isFinite(value)?value:null;}
  catch(ReflectiveOperationException e){return null;}
 }
}
