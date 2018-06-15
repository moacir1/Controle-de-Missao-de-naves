package org.yamcs.xtceproc;

import java.nio.charset.Charset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yamcs.parameter.ParameterValue;
import org.yamcs.parameter.Value;
import org.yamcs.protobuf.Pvalue.AcquisitionStatus;
import org.yamcs.protobuf.Yamcs.Value.Type;
import org.yamcs.utils.BitBuffer;
import org.yamcs.utils.ValueUtility;
import org.yamcs.xtce.BinaryDataEncoding;
import org.yamcs.xtce.BooleanDataEncoding;
import org.yamcs.xtce.DataEncoding;
import org.yamcs.xtce.FloatDataEncoding;
import org.yamcs.xtce.IntegerDataEncoding;
import org.yamcs.xtce.IntegerDataEncoding.Encoding;
import org.yamcs.xtce.StringDataEncoding;
import org.yamcs.xtce.StringDataEncoding.SizeType;

/**
 * Decodes TM data according to the specification of the DataEncoding
 * This is a generic catch all decoder, relies on specific custom decoders implementing
 * the DataDecoder interface when necessary.
 * @see  org.yamcs.xtceproc.DataDecoder
 *
 * @author nm
 *
 */
public class DataEncodingDecoder {
    ProcessorData pdata;
    BitBuffer buffer;
    Logger log = LoggerFactory.getLogger(this.getClass().getName());

    public DataEncodingDecoder(ContainerProcessingContext pcontext) {
        this(pcontext.pdata, pcontext.buffer);
    }

    public DataEncodingDecoder(ProcessorData pdata, BitBuffer buffer) {
        this.pdata = pdata;
        this.buffer = buffer;
    }

    /**
     *  Extracts the raw uncalibrated parameter value from the buffer.
     */
    public void extractRaw(DataEncoding de, ParameterValue pv) {
        if(de.getFromBinaryTransformAlgorithm()!=null) { //custom algorithm
            DataDecoder dd = pdata.getDataDecoder(de);
            pv.setRawValue(dd.extractRaw(de, buffer));
        } else {
            if(de instanceof IntegerDataEncoding) {
                extractRawInteger((IntegerDataEncoding) de, pv);
            } else if(de instanceof FloatDataEncoding) {
                extractRawFloat((FloatDataEncoding) de, pv);
            } else if(de instanceof StringDataEncoding) {
                extractRawString((StringDataEncoding) de, pv);
            } else if(de instanceof BooleanDataEncoding) {
                extractRawBoolean((BooleanDataEncoding) de, pv);
            } else if(de instanceof BinaryDataEncoding) {
                extractRawBinary((BinaryDataEncoding) de, pv);
            } else {
                log.error("DataEncoding {} not implemented", de);
                throw new IllegalArgumentException("DataEncoding "+de+" not implemented");
            }
        }
    }

    private void extractRawInteger(IntegerDataEncoding ide, ParameterValue pv) {
        // Integer encoded as string, don't even try reading it as int
        if(ide.getEncoding() == Encoding.STRING) {
            extractRaw(ide.getStringEncoding(), pv);
            return;
        }

        buffer.setByteOrder(ide.getByteOrder());
        int numBits = ide.getSizeInBits();

        long rv = buffer.getBits(numBits);
        switch(ide.getEncoding()) {
        case UNSIGNED:
            //nothing to do
            break;
        case TWOS_COMPLEMENT:
            int n = 64-numBits;
            //shift left to get the sign and back again
            rv = (rv <<n)>>n;
            break;

        case SIGN_MAGNITUDE:
                boolean negative = ((rv>>>(numBits-1) & 1L) == 1L);

                if (negative) {
                    rv = rv&((1<<(numBits-1))-1); //remove the sign bit
                    rv = -rv;
                }
                break;
        case ONES_COMPLEMENT:
                negative = ((rv>>>(numBits-1) & 1L) == 1L);
                if (negative) {
                    n = 64-numBits;
                    rv = (rv <<n)>>n;
                    rv = ~rv;
                    rv = -rv;
                }
                break;
        default: //shouldn't happen
            throw new IllegalStateException();
        }
        setRawValue(ide, pv, rv);
    }

    private static void setRawValue(IntegerDataEncoding ide, ParameterValue pv, long longValue) {
        if(ide.getSizeInBits() <= 32) {
            if(ide.getEncoding() == Encoding.UNSIGNED) {
                pv.setRawUnsignedInteger((int)longValue);
            } else {
                pv.setRawSignedInteger((int)longValue);
            }
        } else {
            if(ide.getEncoding() == Encoding.UNSIGNED) {
                pv.setRawUnsignedLong(longValue);
            } else {
                pv.setRawSignedLong(longValue);
            }
        }
    }

    private void extractRawString(StringDataEncoding sde, ParameterValue pv) {
        if(buffer.getPosition()%8!=0) {
            log.warn("Binary data that does not start at byte boundary not supported. bitPosition: {}", buffer);
            pv.setAcquisitionStatus(AcquisitionStatus.INVALID);
            return;
        }

        int sizeInBytes=0;
        int totalSizeInBits;
        switch(sde.getSizeType()) {
        case FIXED:
            sizeInBytes = sde.getSizeInBits()>>3;
            totalSizeInBits = sde.getSizeInBits();
        break;
        case LEADING_SIZE:
            sizeInBytes = (int) buffer.getBits(sde.getSizeInBitsOfSizeTag());
            totalSizeInBits = (sizeInBytes << 3) + sde.getSizeInBitsOfSizeTag();
            break;
        case TERMINATION_CHAR:
            int p = buffer.getPosition();
            while(buffer.getByte()!=sde.getTerminationChar()){
                sizeInBytes++;
            }
            buffer.setPosition(p);
            totalSizeInBits = (sizeInBytes << 3) + 8;
            break;
        default: //shouldn't happen, CUSTOM should have an binary transform algorithm 
            throw new IllegalStateException();
        }
        byte[] b = new byte[sizeInBytes];
        buffer.getByteArray(b);

        if(sde.getSizeType()==SizeType.TERMINATION_CHAR) {
            buffer.getByte();//the termination char
        }
        pv.setRawValue(new String(b, Charset.forName(sde.getEncoding())));
        pv.setBitSize(totalSizeInBits);
    }


    private void extractRawFloat(FloatDataEncoding de, ParameterValue pv) {
        switch(de.getEncoding()) {
        case IEEE754_1985:
            extractRawIEEE754_1985(de, pv);
            break;
        case STRING:
            extractRaw(de.getStringDataEncoding(), pv);
            break;
        default:
            throw new IllegalArgumentException("Float Encoding "+de.getEncoding()+" not implemented");
        }
    }

    private void extractRawIEEE754_1985(FloatDataEncoding de, ParameterValue pv) {
        buffer.setByteOrder(de.getByteOrder());

        if(de.getSizeInBits()==32) {
            pv.setRawFloatValue(Float.intBitsToFloat((int) buffer.getBits(32)));
        } else {
            pv.setRawDoubleValue(Double.longBitsToDouble(buffer.getBits(64)));
        }
    }

    private void extractRawBoolean(BooleanDataEncoding bde, ParameterValue pv) {
        pv.setRawValue(buffer.getBits(1) != 0);
    }

    private void extractRawBinary(BinaryDataEncoding bde, ParameterValue pv) {
        if(buffer.getPosition() % 8 != 0) {
            log.warn("Binary Parameter that does not start at byte boundary not supported. bitPosition: {}", buffer);
            pv.setAcquisitionStatus(AcquisitionStatus.INVALID);
            return;
        }

        int sizeInBytes;
        int totalSizeInBits;
        switch(bde.getType()) {
        case FIXED_SIZE:
            sizeInBytes = bde.getSizeInBits()/8;
            totalSizeInBits = (sizeInBytes<<3);
            break;
        case LEADING_SIZE:
            sizeInBytes = (int) buffer.getBits(bde.getSizeInBitsOfSizeTag());
            totalSizeInBits = (sizeInBytes<<3) + bde.getSizeInBitsOfSizeTag();
            break;
        default: //shouldn't happen
            throw new IllegalStateException();
        }
        if(sizeInBytes > buffer.remainingBytes()) {
            throw new IndexOutOfBoundsException("Cannot extract binary parameter of size "+sizeInBytes+". Remaining in the buffer: "+buffer.remainingBytes());
        }
        byte[] b = new byte[sizeInBytes];
        buffer.getByteArray(b);
        pv.setRawValue(b);
        pv.setBitSize(totalSizeInBits);
    }
    
    
    /**
     * return the nominal Value.Type of a raw value corresponding to the given XTCE data encoding definition
     * @param type
     * @return
     */
    public static org.yamcs.protobuf.Yamcs.Value.Type getRawType(DataEncoding encoding) {
        if(encoding instanceof IntegerDataEncoding) {
            IntegerDataEncoding ide = (IntegerDataEncoding)encoding;
            if (ide.getSizeInBits() <= 32) {
                if(ide.getEncoding()==Encoding.UNSIGNED) {
                    return Type.UINT32;
                } else {
                    return Type.SINT32;
                }
            } else {
                if(ide.getEncoding()==Encoding.UNSIGNED) {
                    return Type.UINT32;
                } else {
                    return Type.SINT32;
                }
            }
        } else if(encoding instanceof FloatDataEncoding) {
            FloatDataEncoding fpt = (FloatDataEncoding) encoding;
            if(fpt.getSizeInBits()<=32) {
                return Type.FLOAT;
            } else {
                return Type.DOUBLE;
            }
        } else if (encoding instanceof BooleanDataEncoding) {
            return Type.BOOLEAN;
        } else if (encoding instanceof BinaryDataEncoding) {
            return Type.BINARY;
        } else if (encoding instanceof StringDataEncoding) {
            return Type.STRING;
        } else {
            throw new IllegalStateException("Unknonw data encoding '"+encoding+"'");
        }
    }
    
    

    public static Value getRawValue(DataEncoding de, Object value) {
        if (de instanceof IntegerDataEncoding) {
            return getRawIntegerValue((IntegerDataEncoding) de, value);
        } else if (de instanceof FloatDataEncoding) {
            return getRawFloatValue((FloatDataEncoding) de, value);
        } else if (de instanceof StringDataEncoding) {
            return ValueUtility.getStringValue(value.toString());
        } else if (de instanceof BooleanDataEncoding) {
            if (value instanceof Boolean) {
                return ValueUtility.getBooleanValue((Boolean) value);
            } else {
               return null;
            }
        } else {
            throw new IllegalStateException("Unknown data encoding '"+de+"'");
        }
    }

    static private Value getRawIntegerValue(IntegerDataEncoding ide, Object value) {
        long longValue;
        if (value instanceof Number) {
            longValue = ((Number) value).longValue();
        } else {
            return null;
        }
        //limit the value to the number of defined bits
        longValue = longValue & (-1 >>> (64-ide.getSizeInBits()));
        
        if (ide.getSizeInBits() <= 32) {
            if (ide.getEncoding() == Encoding.UNSIGNED) {
                return ValueUtility.getUint32Value((int) longValue);
            } else {
                return ValueUtility.getSint32Value((int) longValue);
            }
        } else {
            if (ide.getEncoding() == Encoding.UNSIGNED) {
                return ValueUtility.getUint64Value((int) longValue);
            } else {
                return ValueUtility.getSint64Value((int) longValue);
            }
        }
    }

    static private Value getRawFloatValue(FloatDataEncoding fde, Object value) {
        double doubleValue;
        if (value instanceof Number) {
            doubleValue = ((Number) value).doubleValue();
        } else {
           return null;
        }
        if (fde.getSizeInBits() <= 32) {
            return ValueUtility.getFloatValue((float) doubleValue);
        } else {
            return ValueUtility.getDoubleValue(doubleValue);
        }
    }
    
}
