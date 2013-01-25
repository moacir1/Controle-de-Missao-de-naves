package org.yamcs.archive;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yamcs.ConfigurationException;
import org.yamcs.ParameterValue;
import org.yamcs.YConfiguration;
import org.yamcs.management.ManagementService;
import org.yamcs.tctm.PpListener;
import org.yamcs.tctm.PpProvider;
import org.yamcs.utils.TimeEncoding;
import org.yamcs.utils.YObjectLoader;
import org.yamcs.yarch.DataType;
import org.yamcs.yarch.Stream;
import org.yamcs.yarch.Tuple;
import org.yamcs.yarch.TupleDefinition;
import org.yamcs.yarch.YarchDatabase;
import org.yamcs.yarch.streamsql.ParseException;
import org.yamcs.yarch.streamsql.StreamSqlException;

import com.google.common.util.concurrent.AbstractService;

/**
 * PpRecorder
 * 
 * Injects processed parameters from PpProviders into yarch.
 *
 * To the base definition there is one column for each parameter name with the type PROTOBUF({@link org.yamcs.protobuf.pvalue.ParameterValue}

 * @author nm
 *
 */
public class PpProviderAdapter extends AbstractService {
	String archiveInstance;
	private Collection<PpProvider> ppproviders=new ArrayList<PpProvider>();
	final private Logger log;
	
	static public final TupleDefinition PP_TUPLE_DEFINITION=new TupleDefinition();
	//first columns from the PP tuples
	//the actual values are encoded as separated columns (umi_0x010203040506, value) value is binary
	static {
	    PP_TUPLE_DEFINITION.addColumn("gentime", DataType.TIMESTAMP); //generation time
	    PP_TUPLE_DEFINITION.addColumn("ppgroup", DataType.ENUM);
	    PP_TUPLE_DEFINITION.addColumn("seqNum", DataType.INT);
	    PP_TUPLE_DEFINITION.addColumn("rectime", DataType.TIMESTAMP); //recording time
	    
	} 
	
	
	public PpProviderAdapter(String archiveInstance) throws IOException, ConfigurationException, StreamSqlException, ParseException{
        this.archiveInstance=archiveInstance;
        YarchDatabase ydb=YarchDatabase.getInstance(archiveInstance);
        log=LoggerFactory.getLogger(this.getClass().getName()+"["+archiveInstance+"]");
        
        YConfiguration c=YConfiguration.getConfiguration("yamcs."+archiveInstance);
        @SuppressWarnings("rawtypes")
        List providers=c.getList("ppProviders");
        int count=1;
        for(Object o:providers) {
            if(!(o instanceof Map)) throw new ConfigurationException("ppProvider has to be a Map and not a "+o.getClass());
            @SuppressWarnings({ "rawtypes", "unchecked" })
            Map<String, Object> m=(Map)o;
            String className=YConfiguration.getString(m, "class");
            Object args=null;
            if(m.containsKey("args")) {
                args=m.get("args");
            } else if(m.containsKey("spec")) {
                args=m.get("spec");
            }
            String streamName=YConfiguration.getString(m, "stream");
            String providerName="pp"+count;
            boolean enabledAtStartup=true;
            if(m.containsKey("enabledAtStartup")) {
               enabledAtStartup=YConfiguration.getBoolean(m, "enabledAtStartup"); 
            }
            
            Stream s=ydb.getStream(streamName);
            if(s==null) {
                ydb.execute("create stream "+streamName+PP_TUPLE_DEFINITION.getStringDefinition());
                s=ydb.getStream(streamName);
            }
            final Stream stream=s;
            
            YObjectLoader<PpProvider> objloader=new YObjectLoader<PpProvider>();
            
            PpProvider prov= null;
            if(args!=null) {
                prov = objloader.loadObject(className, archiveInstance, args);
            } else {
                prov = objloader.loadObject(className, archiveInstance);
            }
            
            if(!enabledAtStartup) prov.disable();
            
            prov.setPpListener(new PpListener() {
                final DataType paraDataType=DataType.protobuf(org.yamcs.protobuf.Pvalue.ParameterValue.class.getName());
                @Override
                public void updatePps(long gentime, String group, int seqNum, Collection<ParameterValue> params) {
                    TupleDefinition tdef=PP_TUPLE_DEFINITION.copy();
                    List<Object> cols=new ArrayList<Object>(4+params.size());
                    cols.add(gentime);
                    cols.add(group);
                    cols.add(seqNum);
                    cols.add(TimeEncoding.currentInstant());
                    for(ParameterValue pv:params) {
                        int idx=tdef.getColumnIndex(pv.def.getName());
                        if(idx!=-1) {
                            log.warn("duplicate value for "+pv.def+"\nfirst: "+cols.get(idx)+"\n second: "+pv.toGpb(null));
                            continue;
                        }
                        tdef.addColumn(pv.def.getName(), paraDataType);
                        cols.add(pv.toGpb(null));
                    }
                    Tuple t=new Tuple(tdef, cols);
                    stream.emitTuple(t);
                }
            });
            ManagementService.getInstance().registerLink(archiveInstance, providerName, streamName, args!=null?args.toString():"", prov);
            ppproviders.add(prov);
            count++;
        }
    }
	
	@Override
    protected void doStart() {
	    for(PpProvider prov:ppproviders) {
            prov.start();
        }
        notifyStarted();
	}	

	static public void main(String[] args) throws Exception {
		new PpProviderAdapter("test").start();
	}


    @Override
    protected void doStop() {
        for(PpProvider prov:ppproviders) {
            prov.stop();
        }
        notifyStopped();
        
    }

}