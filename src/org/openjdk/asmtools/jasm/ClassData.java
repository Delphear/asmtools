/*
 * Copyright (c) 1996, 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.openjdk.asmtools.jasm;

import java.io.*;
import java.util.ArrayList;

import static org.openjdk.asmtools.jasm.Constants.*;
import static org.openjdk.asmtools.jasm.Tables.*;

/**
 * ClassData
 *
 * This is the main data structure for representing parsed class data. This structure
 * renders directly to a class file.
 *
 */
class ClassData extends MemberData {

    /*-------------------------------------------------------- */
    /* ClassData inner classes */

    /*-------------------------------------------------------- */
    /* ClassData Fields */
    CFVersion cfv = new CFVersion();
    ConstantPool.ConstCell me, father;
    String myClassName;
    AttrData sourceFileNameAttr;
    ArrayList<Argument> interfaces;
    ArrayList<FieldData> fields = new ArrayList<>();
    ArrayList<MethodData> methods = new ArrayList<>();
    DataVectorAttr<InnerClassData> innerClasses = null;
    DataVectorAttr<BootstrapMethodData> bootstrapMethodsAttr = null;
    ModuleAttr moduleAttribute = null;
    Environment env;
    protected ConstantPool pool;


    private static final String DEFAULT_EXTENSION = ".class";
    String fileExtension = DEFAULT_EXTENSION;
    public CDOutputStream cdos;

    /*-------------------------------------------------------- */
    /**
     * init
     *
     * Initializes the ClassData.
     *
     * @param me The constant pool reference to this class
     * @param father The constant pool reference to the super class
     * @param interfaces A list of interfaces that this class implements
     */
    public final void init(int access, ConstantPool.ConstCell me, ConstantPool.ConstCell father, ArrayList<Argument> interfaces) {
        this.access = access;

        // normalize the modifiers to access flags
        if (Modifiers.hasPseudoMod(access)) {
            createPseudoMod();
        }

        this.me = me;
        if (father == null) {
            father = pool.FindCellClassByName("java/lang/Object");
        }
        this.father = father;
        this.interfaces = interfaces;

        cfv.initClassDefaults();
    }

    public final void initAsModule() {
        this.access = RuntimeConstants.ACC_MODULE;
        // this_class" module-info
        this.me = pool.FindCellClassByName("module-info");
        // super_class: zero
        this.father = new ConstantPool.ConstCell(0);

        cfv.initModuleDefaults();
    }

    /**
     * default constructor
     *
     * @param env
     */
    public ClassData(Environment env) {
        super(null, 0);  // for a class, these get inited in the super - later.
        cls = this;

        this.env = env;
        pool = new ConstantPool(env);
        cdos = new CDOutputStream();

    }


    /**
     * canonical default constructor
     *
     * @param env The error reporting environment.
     * @param cfv The class file version that this class file supports.
     */
    public ClassData(Environment env, CFVersion cfv) {
        this(env);
        this.cfv = cfv;
    }

    public ClassData(Environment env, int acc, ConstantPool.ConstCell me, ConstantPool.ConstCell father, ArrayList<Argument> impls) {
        this(env);
        init(acc, me, father, impls);
    }


    /* *********************************************** */
    /**
     * isInterface
     *
     * Predicate that describes if this class has an access flag indicating that it is an
     * interface.
     *
     * @return True if the classes access flag indicates it is an interface.
     */
    public final boolean isInterface() {
        return Modifiers.isInterface(access);
    }

    /*
     * After a constant pool has been explicitly declared,
     * this method links the Constant_InvokeDynamic constants
     * with any bootstrap methods that they index in the
     * Bootstrap Methods Attribute
     */
    protected void relinkBootstrapMethods() {
        if (bootstrapMethodsAttr == null) {
            return;
        }

        env.traceln("relinkBootstrapMethods");

        for (ConstantPool.ConstCell cell : pool) {
            ConstantPool.ConstValue ref = null;
            if (cell != null) {
                ref = cell.ref;
            }
            if (ref != null
                    && ref.tag == ConstType.CONSTANT_INVOKEDYNAMIC) {
                // Find only the Constant
                ConstantPool.ConstValue_IndyPair refval = (ConstantPool.ConstValue_IndyPair) ref;
                if (refval != null) {
                    BootstrapMethodData bsmdata = refval.bsmData;
                    // only care about BSM Data that were placeholders
                    if (bsmdata != null && bsmdata.isPlaceholder()) {
                        // find the real BSM Data at the index
                        int bsmindex = bsmdata.placeholder_index;
                        if (bsmindex < 0 || bsmindex > bootstrapMethodsAttr.size()) {
                            // bad BSM index --
                            // give a warning, but place the index in the arg anyway
                            env.traceln("Warning: (ClassData.relinkBootstrapMethods()): Bad bootstrapMethods index: " + bsmindex);
                            // env.error("const.bsmindex", bsmindex);
                            bsmdata.arg = bsmindex;
                        } else {

                            BootstrapMethodData realbsmdata = bootstrapMethodsAttr.get(bsmindex);
                            // make the IndyPairs BSM Data point to the one from the attribute
                            refval.bsmData = realbsmdata;
                        }
                    }
                }
            }
        }
    }

    protected void numberBootstrapMethods() {
        env.traceln("Numbering Bootstrap Methods");
        if (bootstrapMethodsAttr == null) {
            return;
        }

        int index = 0;
        for (BootstrapMethodData data : bootstrapMethodsAttr) {
            data.arg = index++;
        }
    }

    /*-------------------------------------------------------- API */
    public ConstantPool.ConstValue_Pair mkNape(ConstantPool.ConstCell name, ConstantPool.ConstCell sig) {
        return new ConstantPool.ConstValue_Pair(ConstType.CONSTANT_NAMEANDTYPE, name, sig);
    }

    public ConstantPool.ConstValue_Pair mkNape(String name, String sig) {
        return mkNape(pool.FindCellAsciz(name), pool.FindCellAsciz(sig));
    }

    public void setSourceFileName(String name) {
    }

    public FieldData addField(int access, ConstantPool.ConstValue_Pair nape) {
        env.traceln(" [ClassData.addField]:  #" + nape.left.arg + ":#" + nape.right.arg);
        FieldData res = new FieldData(this, access, nape);
        fields.add(res);
        return res;
    }

    public FieldData addField(int access, ConstantPool.ConstCell name, ConstantPool.ConstCell sig) {
        return addField(access, mkNape(name, sig));
    }

    public FieldData addField(int access, String name, String type) {
        return addField(access, pool.FindCellAsciz(name), pool.FindCellAsciz(type));
    }

    public ConstantPool.ConstCell LocalFieldRef(FieldData field) {
        return pool.FindCell(ConstType.CONSTANT_FIELD, me, pool.FindCell(field.nape));
    }

    public ConstantPool.ConstCell LocalFieldRef(ConstantPool.ConstValue nape) {
        return pool.FindCell(ConstType.CONSTANT_FIELD, me, pool.FindCell(nape));
    }

    public ConstantPool.ConstCell LocalFieldRef(ConstantPool.ConstCell name, ConstantPool.ConstCell sig) {
        return LocalFieldRef(mkNape(name, sig));
    }

    public ConstantPool.ConstCell LocalFieldRef(String name, String sig) {
        return LocalFieldRef(pool.FindCellAsciz(name), pool.FindCellAsciz(sig));
    }

    MethodData curMethod;

    public MethodData StartMethod(int access, ConstantPool.ConstCell name, ConstantPool.ConstCell sig, ArrayList exc_table) {
        EndMethod();
        env.traceln(" [ClassData.StartMethod]:  #" + name.arg + ":#" + sig.arg);
        curMethod = new MethodData(this, access, name, sig, exc_table);
        methods.add(curMethod);
        return curMethod;
    }

    public void EndMethod() {
        curMethod = null;
    }

    public ConstantPool.ConstCell LocalMethodRef(ConstantPool.ConstValue nape) {
        return pool.FindCell(ConstType.CONSTANT_METHOD, me, pool.FindCell(nape));
    }

    public ConstantPool.ConstCell LocalMethodRef(ConstantPool.ConstCell name, ConstantPool.ConstCell sig) {
        return LocalMethodRef(mkNape(name, sig));
    }

    void addLocVarData(int opc, Argument arg) {
    }

    public void addInnerClass(int access, ConstantPool.ConstCell name, ConstantPool.ConstCell innerClass, ConstantPool.ConstCell outerClass) {
        env.traceln("addInnerClass (with indexes: Name (" + name.toString() + "), Inner (" + innerClass.toString() + "), Outer (" + outerClass.toString() + ").");
        if (innerClasses == null) {
            innerClasses = new DataVectorAttr<>(this, AttrTag.ATT_InnerClasses.parsekey());
        }
        innerClasses.add(new InnerClassData(access, name, innerClass, outerClass));
    }

    public void addBootstrapMethod(BootstrapMethodData bsmData) {
        env.traceln("addBootstrapMethod");
        if (bootstrapMethodsAttr == null) {
            bootstrapMethodsAttr = new DataVectorAttr<>(this, AttrTag.ATT_BootstrapMethods.parsekey());
        }
        bootstrapMethodsAttr.add(bsmData);
    }

    public void endClass() {
        sourceFileNameAttr = new CPXAttr(this,
                AttrTag.ATT_SourceFile.parsekey(),
                pool.FindCellAsciz(env.getSourceName()));
        pool.NumberizePool();
        pool.CheckGlobals();
        numberBootstrapMethods();
        try {
            me = pool.uncheckedGetCell(me.arg);
            env.traceln("me=" + me);
            ConstantPool.ConstValue_Cell me_value = (ConstantPool.ConstValue_Cell) me.ref;
            ConstantPool.ConstCell ascicell = me_value.cell;
            env.traceln("ascicell=" + ascicell);
            ConstantPool.ConstValue_String me_str = (ConstantPool.ConstValue_String) ascicell.ref;
            myClassName = me_str.value;
            env.traceln("--------------------------------------------");
            env.traceln("-- Constant Pool --");
            env.traceln("-------------------");
            pool.printPool();
            env.traceln("--------------------------------------------");
            env.traceln(" ");
            env.traceln(" ");
            env.traceln("--------------------------------------------");
            env.traceln("-- Inner Classes --");
            env.traceln("-------------------");
            printInnerClasses();

        } catch (Throwable e) {
            env.traceln("check name:" + e);
            env.error("no.classname");
            e.printStackTrace();
        }
    }

    public void endModule(ModuleAttr moduleAttr) {
        moduleAttribute = moduleAttr.build();
        pool.NumberizePool();
        pool.CheckGlobals();
        myClassName = "module-info";
    }

    private void printInnerClasses() {
        if (innerClasses != null) {
            int i = 1;
            for (InnerClassData entry : innerClasses) {
                env.trace(" InnerClass[" + i + "]: (" + Modifiers.toString(entry.access, CF_Context.CTX_INNERCLASS) + "]), ");
                env.trace("Name:  " + entry.name.toString() + " ");
                env.trace("IC_info:  " + entry.innerClass.toString() + " ");
                env.trace("OC_info:  " + entry.outerClass.toString() + " ");
                env.traceln(" ");
                i += 1;
            }
        } else {
            env.traceln("<< NO INNER CLASSES >>");
        }

    }

    /*====================================================== write */
    public void write(CheckedDataOutputStream out) throws IOException {

        // Write the header
        out.writeInt(JAVA_MAGIC);
        out.writeShort(cfv.minor_version());
        out.writeShort(cfv.major_version());

        pool.write(out);
        out.writeShort(access); // & MM_CLASS; // Q
        out.writeShort(me.arg);
        out.writeShort(father.arg);

        // Write the interface names
        if (interfaces != null) {
            out.writeShort(interfaces.size());
            for (Argument intf : interfaces) {
                out.writeShort(intf.arg);
            }
        } else {
            out.writeShort(0);
        }

        // Write the fields
        if (fields != null) {
            out.writeShort(fields.size());
            for (FieldData field : fields) {
                field.write(out);
            }
        } else {
            out.writeShort(0);
        }

        // Write the methods
        if (methods != null) {
            out.writeShort(methods.size());
            for (MethodData method : methods) {
                method.write(out);
            }
        } else {
            out.writeShort(0);
        }

        DataVector attrs = new DataVector();

        // Write the attributes
        if( moduleAttribute != null ) {
            if (annotAttrVis != null)
                attrs.add(annotAttrVis);
            if (annotAttrInv != null)
                attrs.add(annotAttrInv);
            attrs.add(moduleAttribute);
        } else {
            attrs.add(sourceFileNameAttr);
            if (innerClasses != null)
                attrs.add(innerClasses);
            if (syntheticAttr != null)
                attrs.add(syntheticAttr);
            if (deprecatedAttr != null)
                attrs.add(deprecatedAttr);
            if (annotAttrVis != null)
                attrs.add(annotAttrVis);
            if (annotAttrInv != null)
                attrs.add(annotAttrInv);
            if (type_annotAttrVis != null)
                attrs.add(type_annotAttrVis);
            if (type_annotAttrInv != null)
                attrs.add(type_annotAttrInv);
            if (bootstrapMethodsAttr != null)
                attrs.add(bootstrapMethodsAttr);
        }
        attrs.write(out);
    } // end ClassData.write()

    static char fileSeparator; //=System.getProperty("file.separator");

    /**
     * write to the directory passed with -d option
     */
    public void write(File destdir) throws IOException {
        File outfile;
        if (destdir == null) {
            int startofname = myClassName.lastIndexOf("/");
            if (startofname != -1) {
                myClassName = myClassName.substring(startofname + 1);
            }
            outfile = new File(myClassName + fileExtension);
        } else {
            env.traceln("writing -d " + destdir.getPath());
            if (fileSeparator == 0) {
                fileSeparator = System.getProperty("file.separator").charAt(0);
            }
            if (fileSeparator != '/') {
                myClassName = myClassName.replace('/', fileSeparator);
            }
            outfile = new File(destdir, myClassName + fileExtension);
            File outdir = new File(outfile.getParent());
            if (!outdir.exists() && !outdir.mkdirs()) {
                env.error("cannot.write", outdir.getPath());
                return;
            }
        }

        DataOutputStream dos = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(outfile)));
        cdos.setDataOutputStream(dos);
        try {
            write(cdos);
        } finally {
            dos.close();
        }
    }  // end write()

    public void setByteLimit(int bytelimit) {
        cdos.enable();
        cdos.setLimit(bytelimit);
    }

    /**
     * CDOutputStream
     *
     * This is a wrapper for DataOutputStream, used for debugging purposes. it allows
     * writing the byte-stream of a class up to a given byte number.
     */
    static private class CDOutputStream implements CheckedDataOutputStream {

        private int bytelimit;
        private DataOutputStream dos;
        public boolean enabled = false;

        public CDOutputStream() {
            dos = null;
        }

        public CDOutputStream(OutputStream out) {
            setOutputStream(out);
        }

        public final void setOutputStream(OutputStream out) {
            dos = new DataOutputStream(out);
        }

        public void setDataOutputStream(DataOutputStream dos) {
            this.dos = dos;
        }

        public void setLimit(int lim) {
            bytelimit = lim;
        }

        public void enable() {
            enabled = true;
        }

        private synchronized void check(String loc) throws IOException {
            if (enabled && dos.size() >= bytelimit) {
                throw new IOException(loc);
            }
        }

        @Override
        public synchronized void write(int b) throws IOException {
            dos.write(b);
            check("Writing byte: " + b);
        }

        @Override
        public synchronized void write(byte b[], int off, int len) throws IOException {
            dos.write(b, off, len);
            check("Writing byte-array: " + b);
        }

        @Override
        public final void writeBoolean(boolean v) throws IOException {
            dos.writeBoolean(v);
            check("Writing writeBoolean: " + (v ? "true" : "false"));
        }

        @Override
        public final void writeByte(int v) throws IOException {
            dos.writeByte(v);
            check("Writing writeByte: " + v);
        }

        @Override
        public void writeShort(int v) throws IOException {
            dos.writeShort(v);
            check("Writing writeShort: " + v);
        }

        @Override
        public void writeChar(int v) throws IOException {
            dos.writeChar(v);
            check("Writing writeChar: " + v);
        }

        @Override
        public void writeInt(int v) throws IOException {
            dos.writeInt(v);
            check("Writing writeInt: " + v);
        }

        @Override
        public void writeLong(long v) throws IOException {
            dos.writeLong(v);
            check("Writing writeLong: " + v);
        }

        @Override
        public void writeFloat(float v) throws IOException {
            dos.writeFloat(v);
            check("Writing writeFloat: " + v);
        }

        @Override
        public void writeDouble(double v) throws IOException {
            dos.writeDouble(v);
            check("Writing writeDouble: " + v);
        }

        @Override
        public void writeBytes(String s) throws IOException {
            dos.writeBytes(s);
            check("Writing writeBytes: " + s);
        }

        @Override
        public void writeChars(String s) throws IOException {
            dos.writeChars(s);
            check("Writing writeChars: " + s);
        }

        @Override
        public void writeUTF(String s) throws IOException {
            dos.writeUTF(s);
            check("Writing writeUTF: " + s);
        }
        /*
         public int writeUTF(String str, DataOutput out) throws IOException{
         int ret = dos.writeUTF(str, out);
         check("Writing writeUTF: " + str);
         return ret;
         }
         * */

    }
}// end class ClassData
