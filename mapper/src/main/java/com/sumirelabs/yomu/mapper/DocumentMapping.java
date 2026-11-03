package com.sumirelabs.yomu.mapper;

import com.sumirelabs.yomu.*;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.*;

/** Operation-local observations for document overlays. Ordinary mapping never allocates these tables. */
final class DocumentMapping {
    final Json5Mapper mapper;
    final Json5Document source;
    private final Type type;
    private final Session read;
    private final Projection before;
    private final IdentityHashMap<Object,Integer> stableIds=new IdentityHashMap<Object,Integer>();
    private int nextId;

    static <T> MappedDocument<T> open(Json5Mapper mapper,Json5Document source,Type type) {
        return new DocumentMapping(mapper,source,type).open();
    }
    private DocumentMapping(Json5Mapper mapper,Json5Document source,Type type) {
        this.mapper=mapper;this.source=source;this.type=type;this.read=new Session(mapper);
        Object wire=source.getValue();if(read.graph!=null)wire=read.graph.open(wire);
        Object value=read.read(wire,type,Collections.<TypeVariable<?>,Type>emptyMap(),"$",0);
        initial=value;
        if(read.graph!=null) {
            nextId=read.graph.nodes.size();
            for(int i=0;i<read.graph.objects.length;i++)if(read.graph.objects[i]!=null) {
                Object object=read.graph.objects[i];
                if(stableIds.containsKey(object)&&!read.graph.scalar(object))throw new MappingException("$","Distinct graph nodes decoded to the same object; source identities are ambiguous");
                if(!stableIds.containsKey(object))stableIds.put(object,i);
            }
        }
        before=project(value,null,true);
        read.originals=null; // Translation was only needed while decoding detached codec/Object inputs.
    }
    private Object initial;
    @SuppressWarnings("unchecked") private <T> MappedDocument<T> open() {
        T value=(T)initial;initial=null;return new MappedDocument<T>(this,value);
    }
    Json5Document document(Object value) {
        Projection after=project(value,before,false);
        Object merged;
        if(mapper.objectGraph) {
            Map<?,?> original=(Map<?,?>)source.getValue();List<?> oldNodes=(List<?>)original.get("nodes");
            List<Object> nodes=new ArrayList<Object>(oldNodes);
            while(nodes.size()<after.nodeCount)nodes.add(emptyNode());
            for(Map.Entry<Integer,Object> entry:after.nodes.entrySet()) {
                int id=entry.getKey();Object current=entry.getValue(),prior=before.nodes.get(id);
                if(id<oldNodes.size()&&prior!=null) {
                    Map<?,?> old=(Map<?,?>)oldNodes.get(id),a=(Map<?,?>)prior,b=(Map<?,?>)current;
                    if(Objects.equals(a.get("kind"),b.get("kind"))&&Objects.equals(a.get("type"),b.get("type"))) {
                        Map<String,Object> node=mapCopy(b);
                        node.put("value",merge(old.get("value"),a.get("value"),b.get("value"),after,before,0));current=node;
                    }
                }
                nodes.set(id,current);
            }
            Map<String,Object> result=new LinkedHashMap<String,Object>();result.put("$thermometer","graph/1");
            result.put("root",same(before.root,after.root)?original.get("root"):after.root);result.put("nodes",nodes);
            new MappingSession(mapper).graph.open(result);merged=result;
        } else merged=merge(source.getValue(),before.root,after.root,after,before,0);
        return source.withValue(merged);
    }
    private static Map<String,Object> emptyNode() {Map<String,Object> node=new LinkedHashMap<String,Object>();node.put("kind","map");node.put("value",new LinkedHashMap<String,Object>());return node;}
    private Projection project(Object value,Projection previous,boolean baseline) {
        Session session=new Session(mapper);Object root=session.write(value,type,Collections.<TypeVariable<?>,Type>emptyMap(),"$",0);
        Projection result=new Projection(mapper.objectGraph);
        if(session.graph!=null) {
            MappingGraph graph=session.graph;Object[] owners=new Object[graph.nodes.size()];
            for(Map.Entry<Object,Integer> e:graph.ids.entrySet())owners[e.getValue()]=e.getKey();
            int[] ids=new int[owners.length];Arrays.fill(ids,-1);int next=nextId;
            for(int i=0;i<ids.length;i++){Integer old=stableIds.get(owners[i]);if(old!=null)ids[i]=old;}
            if(previous!=null)pair(previous.root,root,previous,session,owners,ids,new HashSet<String>());
            for(int i=0;i<ids.length;i++)if(ids[i]<0)ids[i]=next++;
            for(Map.Entry<Map<String,Object>,Integer> e:session.references.entrySet()) {
                Map<String,Object> reference=e.getKey();
                if(reference.size()==1&&Objects.equals(reference.get("$ref"),e.getValue())&&ids[e.getValue()]!=e.getValue())reference.put("$ref",ids[e.getValue()]);
            }
            for(int i=0;i<ids.length;i++) {
                result.nodes.put(ids[i],graph.nodes.get(i));result.owners.put(ids[i],owners[i]);
                if(baseline)stableIds.put(owners[i],ids[i]);
            }
            result.nodeCount=next;if(baseline)nextId=next;
        }
        IdentityHashMap<Object,Object> copies=new IdentityHashMap<Object,Object>();result.root=copy(root,copies);
        for(Map.Entry<Integer,Object> e:result.nodes.entrySet())e.setValue(copy(e.getValue(),copies));
        if(session.info!=null)for(Map.Entry<Object,Info> e:session.info.entrySet()) {
            Object key=copies.get(e.getKey());if(key!=null)result.info.put(key,e.getValue());
        }
        if(session.byObject!=null)for(Map.Entry<Object,Object> e:session.byObject.entrySet()) {
            Object wire=copies.get(plain(e.getValue()));if(wire!=null)result.byObject.put(e.getKey(),wire);
        }
        return result;
    }
    private void pair(Object old,Object fresh,Projection previous,Session session,Object[] owners,int[] ids,Set<String> visited) {
        MappingGraph graph=session.graph;
        old=plain(old);fresh=plain(fresh);
        Integer id=session.references.get(fresh);
        if(id!=null && old instanceof Map && ((Map<?,?>)old).size()==1 && ((Map<?,?>)old).get("$ref") instanceof Number) {
            int prior=((Number)((Map<?,?>)old).get("$ref")).intValue();Object owner=previous.owners.get(prior);
            if(!visited.add(prior+":"+id))return;
            if(ids[id]<0 && owner!=null && owners[id]!=null && owner.getClass()==owners[id].getClass()
                    && !graph.ids.containsKey(owner) && !contains(ids,prior))ids[id]=prior;
            if(ids[id]==prior && previous.nodes.containsKey(prior)) {
                Map<?,?> a=(Map<?,?>)previous.nodes.get(prior),b=(Map<?,?>)graph.nodes.get(id);
                if(Objects.equals(a.get("kind"),b.get("kind"))&&Objects.equals(a.get("type"),b.get("type")))pair(a.get("value"),b.get("value"),previous,session,owners,ids,visited);
            }
        } else if(old instanceof Map && fresh instanceof Map) {
            for(Object key:((Map<?,?>)old).keySet())if(((Map<?,?>)fresh).containsKey(key))pair(((Map<?,?>)old).get(key),((Map<?,?>)fresh).get(key),previous,session,owners,ids,visited);
        } else if(old instanceof List && fresh instanceof List) {
            for(int i=0;i<Math.min(((List<?>)old).size(),((List<?>)fresh).size());i++)pair(((List<?>)old).get(i),((List<?>)fresh).get(i),previous,session,owners,ids,visited);
        }
    }
    private boolean hasSource(Object value){return read.sources!=null&&read.sources.containsKey(value)&&(read.ambiguous==null||!read.ambiguous.containsKey(value));}
    private static boolean trackable(Info info) {return info!=null&&!info.opaque&&info.owner!=null&&!(info.owner instanceof String)&&!(info.owner instanceof Number)&&!(info.owner instanceof Boolean)&&!(info.owner instanceof Character)&&!(info.owner instanceof Enum);}
    private static boolean contains(int[] values,int n){for(int value:values)if(value==n)return true;return false;}
    private Object merge(Object original,Object old,Object fresh,Projection now,Projection prior,int depth) {
        return merge(original,old,fresh,now,prior,depth,false);
    }
    private Object merge(Object original,Object old,Object fresh,Projection now,Projection prior,int depth,boolean literalBody) {
        if(depth>512)throw new MappingException("$","Maximum document merge depth exceeded");
        Info freshInfo=now.info.get(plain(fresh)),oldInfo=prior.info.get(plain(old));
        if(trackable(freshInfo)&&(oldInfo==null||freshInfo.owner!=oldInfo.owner)&&hasSource(freshInfo.owner)&&prior.byObject.containsKey(freshInfo.owner)) {
            original=read.sources.get(freshInfo.owner);old=prior.byObject.get(freshInfo.owner);
        }
        if(same(old,fresh)&&!moved(old,fresh,now,prior))return original;
        Object oldPlain=plain(old),newPlain=plain(fresh),sourcePlain=plain(original);
        boolean wrappers=!literalBody&&!mapper.objectGraph&&!mapper.registeredTypes.byId.isEmpty();
        String oldEnvelope=wrappers?envelope(oldPlain):null,newEnvelope=wrappers?envelope(newPlain):null,sourceEnvelope=wrappers?envelope(sourcePlain):null;
        if(oldEnvelope!=null||newEnvelope!=null) {
            if(!Objects.equals(oldEnvelope,newEnvelope))return fresh;
            Map<?,?> a=(Map<?,?>)oldPlain,b=(Map<?,?>)newPlain;
            if(!Objects.equals(a.get("type"),b.get("type")))return fresh;
            Object payload=merge(sourceEnvelope!=null?((Map<?,?>)sourcePlain).get("value"):sourcePlain,a.get("value"),b.get("value"),now,prior,depth+1,true);
            if(sourceEnvelope==null)return payload;
            Map<String,Object> result=mapCopy((Map<?,?>)sourcePlain);result.put("value",payload);return result;
        }
        if(sourceEnvelope!=null) {
            Map<String,Object> result=mapCopy((Map<?,?>)sourcePlain);
            result.put("value",merge(result.get("value"),old,fresh,now,prior,depth+1,true));return result;
        }
        Info info=now.info.get(newPlain),previous=prior.info.get(oldPlain);
        if(info!=null&&info.opaque || previous!=null&&previous.opaque)return fresh;
        if(info!=null&&previous!=null&&info.owner.getClass()!=previous.owner.getClass())return fresh;
        if(sourcePlain instanceof Map && oldPlain instanceof Map && newPlain instanceof Map) {
            Map<?,?> sourceMap=(Map<?,?>)sourcePlain,a=(Map<?,?>)oldPlain,b=(Map<?,?>)newPlain;
            Map<String,Object> result=mapCopy(sourceMap);Set<Object> keys=new LinkedHashSet<Object>(a.keySet());keys.addAll(b.keySet());
            for(Object raw:keys) {
                String key=(String)raw;if(a.containsKey(key)==b.containsKey(key)&&same(a.get(key),b.get(key)))continue;
                String input=key;
                if(!sourceMap.containsKey(input)&&info!=null&&info.model!=null) {
                    ObjectModel.Property field=info.model.fields.get(key);
                    if(field!=null)for(String alias:field.options.aliases)if(sourceMap.containsKey(alias)){input=alias;break;}
                }
                if(!b.containsKey(key))result.remove(input);
                else result.put(input,sourceMap.containsKey(input)&&a.containsKey(key)
                        ?merge(sourceMap.get(input),a.get(key),b.get(key),now,prior,depth+1):merge(null,null,b.get(key),now,prior,depth+1));
            }
            return result;
        }
        if(sourcePlain instanceof List && oldPlain instanceof List && newPlain instanceof List) {
            List<?> sourceList=(List<?>)sourcePlain,a=(List<?>)oldPlain,b=(List<?>)newPlain;List<Object> result=new ArrayList<Object>(b.size());
            if(info!=null&&previous!=null&&info.keys!=null&&previous.keys!=null)
                return entries(sourceList,a,b,info,previous,now,prior,depth);
            for(int i=0;i<b.size();i++) {
                Object next=b.get(i),base=i<a.size()?a.get(i):null,raw=i<sourceList.size()?sourceList.get(i):null;
                Info element=now.info.get(plain(next));
                if(trackable(element) && hasSource(element.owner) && prior.byObject.containsKey(element.owner)) {
                    raw=read.sources.get(element.owner);base=prior.byObject.get(element.owner);
                } else if(i>=a.size()||i>=sourceList.size()){result.add(next);continue;}
                result.add(merge(raw,base,next,now,prior,depth+1));
            }
            return result;
        }
        return fresh;
    }
    private Object entries(List<?> source,List<?> before,List<?> after,Info current,Info original,Projection now,Projection prior,int depth) {
        List<Object> result=new ArrayList<Object>(after.size());
        for(int i=0;i<after.size();i++) {
            List<?> pair=(List<?>)plain(after.get(i));int found=-1;
            for(int j=0;j<before.size();j++)if(same(((List<?>)plain(before.get(j))).get(0),pair.get(0))
                    || i<current.keys.size()&&j<original.keys.size()&&Objects.equals(current.keys.get(i),original.keys.get(j))){found=j;break;}
            if(found<0){List<Object> added=new ArrayList<Object>(2);for(Object part:pair)added.add(merge(null,null,part,now,prior,depth+1));result.add(added);continue;}
            List<?> base=(List<?>)plain(before.get(found));Object key=found<original.keys.size()?original.keys.get(found):null;
            Object raw=hasSource(key)?read.sources.get(key):base.get(0);List<?> input=null;
            for(Object entry:source){List<?> candidate=(List<?>)plain(entry);if(keySame(candidate.get(0),raw,key)){input=candidate;break;}}
            if(input==null)throw new MappingException("$","Cannot associate a preserved map entry with its original key");
            List<Object> merged=new ArrayList<Object>(2);
            merged.add(merge(input.get(0),base.get(0),pair.get(0),now,prior,depth+1));
            merged.add(merge(input.get(1),base.get(1),pair.get(1),now,prior,depth+1));result.add(merged);
        }
        return result;
    }
    private static boolean keySame(Object a,Object b,Object key) {
        if(same(a,b))return true;
        if(a instanceof Number&&b instanceof Number) {
            if(key instanceof Double||key instanceof Float) {
                double x=((Number)a).doubleValue(),y=((Number)b).doubleValue();
                if(x==0&&y==0)return Double.doubleToRawLongBits(x)==Double.doubleToRawLongBits(y);
            }
            try{return new java.math.BigDecimal(a.toString()).compareTo(new java.math.BigDecimal(b.toString()))==0;}
            catch(NumberFormatException invalid){return Double.compare(((Number)a).doubleValue(),((Number)b).doubleValue())==0;}
        }
        return false;
    }
    private boolean moved(Object old,Object fresh,Projection now,Projection prior) {
        old=plain(old);fresh=plain(fresh);Info a=prior.info.get(old),b=now.info.get(fresh);
        if(a!=null&&trackable(b)&&a.owner!=b.owner&&hasSource(b.owner)&&prior.byObject.containsKey(b.owner))return true;
        if(old instanceof Map&&fresh instanceof Map) {
            for(Object key:((Map<?,?>)old).keySet())if(moved(((Map<?,?>)old).get(key),((Map<?,?>)fresh).get(key),now,prior))return true;
        } else if(old instanceof List&&fresh instanceof List) {
            for(int i=0;i<Math.min(((List<?>)old).size(),((List<?>)fresh).size());i++)if(moved(((List<?>)old).get(i),((List<?>)fresh).get(i),now,prior))return true;
        }
        return false;
    }
    private static String envelope(Object value) {
        if(!(value instanceof Map))return null;Map<?,?> map=(Map<?,?>)value;
        Object marker=map.get("$thermometer");
        return map.size()==3&&"type/1".equals(marker)&&map.containsKey("type")&&map.containsKey("value")?"type/1"
                :map.size()==2&&"literal/1".equals(marker)&&map.containsKey("value")?"literal/1":null;
    }
    private static Object plain(Object value){while(value instanceof CommentedValue)value=((CommentedValue)value).getValue();return value;}
    private static Map<String,Object> mapCopy(Map<?,?> source){Map<String,Object> result=new LinkedHashMap<String,Object>();for(Map.Entry<?,?> e:source.entrySet())result.put((String)e.getKey(),e.getValue());return result;}
    private static boolean same(Object a,Object b) {
        a=plain(a);b=plain(b);if(a==b)return true;if(a==null||b==null)return false;
        if(a instanceof Map&&b instanceof Map) {Map<?,?> x=(Map<?,?>)a,y=(Map<?,?>)b;if(!x.keySet().equals(y.keySet()))return false;for(Object key:x.keySet())if(!same(x.get(key),y.get(key)))return false;return true;}
        if(a instanceof List&&b instanceof List) {List<?> x=(List<?>)a,y=(List<?>)b;if(x.size()!=y.size())return false;for(int i=0;i<x.size();i++)if(!same(x.get(i),y.get(i)))return false;return true;}
        return a.equals(b);
    }
    private static Object copy(Object value,IdentityHashMap<Object,Object> copies) {
        if(value instanceof CommentedValue){CommentedValue c=(CommentedValue)value;return new CommentedValue(c.getComment(),copy(c.getValue(),copies));}
        if(value instanceof Map) {Object old=copies.get(value);if(old!=null)return old;Map<String,Object> out=new LinkedHashMap<String,Object>();copies.put(value,out);for(Map.Entry<?,?> e:((Map<?,?>)value).entrySet())out.put((String)e.getKey(),copy(e.getValue(),copies));return out;}
        if(value instanceof List) {Object old=copies.get(value);if(old!=null)return old;List<Object> out=new ArrayList<Object>();copies.put(value,out);for(Object x:(List<?>)value)out.add(copy(x,copies));return out;}
        return value;
    }
    private static final class Projection {
        Object root;int nodeCount;
        final Map<Integer,Object> nodes;
        final Map<Integer,Object> owners;
        Projection(boolean graph){nodes=graph?new LinkedHashMap<Integer,Object>():Collections.<Integer,Object>emptyMap();owners=graph?new HashMap<Integer,Object>():Collections.<Integer,Object>emptyMap();}
        final IdentityHashMap<Object,Info> info=new IdentityHashMap<Object,Info>();
        final IdentityHashMap<Object,Object> byObject=new IdentityHashMap<Object,Object>();
    }
    private static final class Info {
        final Object owner;final ObjectModel model;final boolean opaque;
        final List<?> keys;
        Info(Object owner,ObjectModel model,boolean opaque){this.owner=owner;this.model=model;this.opaque=opaque;keys=!opaque&&owner instanceof Map?new ArrayList<Object>(((Map<?,?>)owner).keySet()):null;}
    }
    private static final class Session implements MappingObserver {
        final Json5Mapper mapper;
        final MappingSession delegate;
        final MappingGraph graph;
        IdentityHashMap<Object,Object> sources;
        IdentityHashMap<Object,Boolean> ambiguous;
        IdentityHashMap<Object,Object> originals;
        IdentityHashMap<Object,Info> info;
        IdentityHashMap<Object,Object> byObject;
        final IdentityHashMap<Map<String,Object>,Integer> references;
        Session(Json5Mapper mapper){this.mapper=mapper;delegate=new MappingSession(mapper,this);graph=delegate.graph;references=mapper.objectGraph?new IdentityHashMap<Map<String,Object>,Integer>():null;}
        Object read(Object value,Type type,Map<TypeVariable<?>,Type> variables,String path,int depth){return delegate.read(value,type,variables,path,depth);}
        Object write(Object value,Type type,Map<TypeVariable<?>,Type> variables,String path,int depth){return delegate.write(value,type,variables,path,depth);}
        public Object onRead(Object source,Object value,boolean codec) {
            Object original=originals!=null&&originals.containsKey(source)?originals.get(source):source;
            if(value==source&&source==original&&(source instanceof Map||source instanceof List))value=codecInput(source);
            if(value!=null&&(codec||source instanceof Map||source instanceof List)) {
                if(sources==null)sources=new IdentityHashMap<Object,Object>();
                if(sources.containsKey(value)) {
                    Object previous=sources.get(value);
                    boolean different=original instanceof Map||original instanceof List||previous instanceof Map||previous instanceof List?original!=previous:!Objects.equals(original,previous);
                    if(different){if(ambiguous==null)ambiguous=new IdentityHashMap<Object,Boolean>();ambiguous.put(value,Boolean.TRUE);}
                }
                sources.put(value,original);
            }
            return value;
        }
        public Object codecInput(Object source) {
            if(!(source instanceof Map)&&!(source instanceof List))return source;
            IdentityHashMap<Object,Object> copies=new IdentityHashMap<Object,Object>();Object result=copy(source,copies);
            if(originals==null)originals=new IdentityHashMap<Object,Object>();
            for(Map.Entry<Object,Object> entry:copies.entrySet())originals.put(entry.getValue(),originals.containsKey(entry.getKey())?originals.get(entry.getKey()):entry.getKey());
            return result;
        }
        public void onWrite(Object value,Object result,String path,boolean codec) {
            Object wire=plain(result);
            if(wire instanceof Map||wire instanceof List) {
                if(info==null){info=new IdentityHashMap<Object,Info>();byObject=new IdentityHashMap<Object,Object>();}
                if(codec)info.put(wire,new Info(value,null,true));
                else if(!info.containsKey(wire)) {
                    ObjectModel model=value!=null && !(value instanceof Map) && !(value instanceof Iterable) && !value.getClass().isArray()
                            && !(value instanceof CommentedValue)?mapper.model(value.getClass(),path):null;
                    info.put(wire,new Info(value,model,false));
                }
                if(value!=null)byObject.put(value,wire);
            }
        }
        public void onReference(Map<String,Object> reference,int id){references.put(reference,id);}
    }
}
