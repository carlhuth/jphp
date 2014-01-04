package ru.regenix.jphp.runtime.ext.core;

import ru.regenix.jphp.annotation.Runtime;
import ru.regenix.jphp.compiler.AbstractCompiler;
import ru.regenix.jphp.compiler.common.compile.FunctionsContainer;
import ru.regenix.jphp.compiler.jvm.JvmCompiler;
import ru.regenix.jphp.exceptions.ParseException;
import ru.regenix.jphp.exceptions.support.ErrorException;
import ru.regenix.jphp.exceptions.support.ErrorType;
import ru.regenix.jphp.runtime.env.CallStackItem;
import ru.regenix.jphp.runtime.env.Context;
import ru.regenix.jphp.runtime.env.Environment;
import ru.regenix.jphp.runtime.env.TraceInfo;
import ru.regenix.jphp.runtime.invoke.Invoker;
import ru.regenix.jphp.runtime.lang.Closure;
import ru.regenix.jphp.runtime.lang.ForeachIterator;
import ru.regenix.jphp.runtime.lang.IObject;
import ru.regenix.jphp.runtime.lang.Resource;
import ru.regenix.jphp.runtime.memory.ArrayMemory;
import ru.regenix.jphp.runtime.memory.LongMemory;
import ru.regenix.jphp.runtime.memory.ObjectMemory;
import ru.regenix.jphp.runtime.memory.StringMemory;
import ru.regenix.jphp.runtime.memory.support.Memory;
import ru.regenix.jphp.runtime.reflection.*;
import ru.regenix.jphp.runtime.util.StackTracer;
import ru.regenix.jphp.syntax.SyntaxAnalyzer;
import ru.regenix.jphp.tokenizer.GrammarUtils;
import ru.regenix.jphp.tokenizer.Tokenizer;

import java.lang.reflect.InvocationTargetException;

public class LangFunctions extends FunctionsContainer {

    protected final static LangConstants constants = new LangConstants();

    private static String evalErrorMessage(ErrorException e){
        return e.getMessage() + ", eval()'s code on line " + (e.getTraceInfo().getStartLine() + 1)
                + ", position " + (e.getTraceInfo().getStartPosition() + 1);
    }

    protected final static ThreadLocal<SyntaxAnalyzer> syntaxAnalyzer = new ThreadLocal<SyntaxAnalyzer>(){
        @Override
        protected SyntaxAnalyzer initialValue() {
            return new SyntaxAnalyzer(null, null);
        }
    };

    public static Memory eval(Environment env, TraceInfo trace, @Runtime.GetLocals ArrayMemory locals, String code)
            throws Throwable {
        Context context = new Context(env, code);
        try {
            Tokenizer tokenizer = new Tokenizer(context);
            SyntaxAnalyzer analyzer = syntaxAnalyzer.get();
            analyzer.reset(env, tokenizer);
            AbstractCompiler compiler = new JvmCompiler(env, context, analyzer);

            ModuleEntity module = compiler.compile();
            env.scope.loadModule(module);
            env.registerModule(module);

            return module.include(env, locals);
        } catch (ErrorException e){
            if (e.getType() == ErrorType.E_PARSE){
                if (env.isHandleErrors(ErrorType.E_PARSE))
                    throw new ParseException(evalErrorMessage(e), trace);
            } else
                env.error(trace, e.getType(), e.getMessage());
        }
        return Memory.FALSE;
    }

    public static Memory compact(@Runtime.GetLocals ArrayMemory locals, Memory varName, Memory... varNames){
        ArrayMemory result = new ArrayMemory();
        Memory value = locals.valueOfIndex(varName).toValue();
        if (value != Memory.UNDEFINED)
            result.refOfIndex(varName).assign(value.toImmutable());

        if (varNames != null){
            for(Memory el : varNames){
                value = locals.valueOfIndex(el).toValue();
                if (value != Memory.UNDEFINED)
                    result.refOfIndex(el).assign(value.toImmutable());
            }
        }

        return result.toImmutable();
    }

    public static int error_reporting(Environment env, int level){
        int old = env.getErrorFlags();
        env.setErrorFlags(level);
        return old;
    }

    public static Memory get_defined_vars(Environment env, TraceInfo trace, @Runtime.GetLocals ArrayMemory locals){
        return locals.toImmutable();
    }

    public static int extract(Environment env, TraceInfo trace, @Runtime.GetLocals ArrayMemory locals,
                              @Runtime.Reference Memory array, int extractType){
        return extract(env, trace, locals, array, extractType, Memory.NULL);
    }

    public static int extract(Environment env, TraceInfo trace, @Runtime.GetLocals ArrayMemory locals,
                              @Runtime.Reference Memory array){
        return extract(env, trace, locals, array, LangConstants.EXTR_OVERWRITE, Memory.NULL);
    }

    public static int extract(Environment env, TraceInfo trace, @Runtime.GetLocals ArrayMemory locals,
                                 @Runtime.Reference Memory array, int extractType, Memory _prefix){
        if (expecting(env, trace, 1, array, Memory.Type.ARRAY)){
            boolean isRefs = (extractType & LangConstants.EXTR_REFS) == LangConstants.EXTR_REFS;
            ForeachIterator iterator = array.getNewIterator(env, isRefs, false);
            int count = 0;
            if (extractType == LangConstants.EXTR_PREFIX_ALL || extractType == LangConstants.EXTR_PREFIX_IF_EXISTS
                    || extractType == LangConstants.EXTR_PREFIX_INVALID || extractType == LangConstants.EXTR_PREFIX_SAME)
                if (_prefix.isNull()) {
                    env.warning(trace, "extract(): specified extract type requires the prefix parameter");
                    return 0;
                }

            String prefix = _prefix.isNull() ? "" : _prefix.concat("_");
            if (!prefix.isEmpty())
                if (!GrammarUtils.isValidName(prefix)) {
                    env.warning(trace, "extract(): prefix is not a valid identifier");
                    return 0;
                }

            while (iterator.next()){
                Object key = iterator.getKey();
                String keyS = key.toString();
                String var;
                Memory value = iterator.getValue();

                if (!isRefs)
                    value = value.toImmutable();

                switch (extractType){
                    case LangConstants.EXTR_OVERWRITE:
                        if (GrammarUtils.isValidName(keyS)) {
                            locals.refOfIndex(keyS).assign(value);
                            count++;
                        }
                        break;
                    case LangConstants.EXTR_SKIP:
                        if (GrammarUtils.isValidName(keyS) && locals.valueOfIndex(keyS).isUndefined()){
                            locals.refOfIndex(keyS).assign(value);
                            count++;
                        }
                        break;
                    case LangConstants.EXTR_PREFIX_SAME:
                        if (!locals.valueOfIndex(keyS).isUndefined()) {
                            var = prefix.concat(keyS);
                            if (GrammarUtils.isValidName(var)) {
                                locals.refOfIndex(var).assign(value);
                                count++;
                            }
                        } else if (GrammarUtils.isValidName(keyS)){
                            locals.refOfIndex(keyS).assign(value);
                            count++;
                        }
                        break;
                    case LangConstants.EXTR_PREFIX_ALL:
                        var = prefix.concat(keyS);
                        if (GrammarUtils.isValidName(var)) {
                            locals.refOfIndex(prefix.concat(keyS)).assign(value);
                            count++;
                        }
                        break;
                    case LangConstants.EXTR_PREFIX_INVALID:
                        if (GrammarUtils.isValidName(keyS)) {
                            locals.refOfIndex(keyS).assign(value);
                            count++;
                        } else {
                            var = prefix.concat(keyS);
                            if (GrammarUtils.isValidName(var)) {
                                locals.refOfIndex(var).assign(value);
                                count++;
                            }
                        }
                        break;
                    case LangConstants.EXTR_IF_EXISTS:
                        if (GrammarUtils.isValidName(keyS))
                        if (!locals.valueOfIndex(keyS).isUndefined()) {
                            locals.refOfIndex(keyS).assign(value);
                            count++;
                        }
                        break;
                    case LangConstants.EXTR_PREFIX_IF_EXISTS:
                        if (!locals.valueOfIndex(keyS).isUndefined()){
                            var = prefix.concat(keyS);
                            if (GrammarUtils.isValidName(var)) {
                                locals.refOfIndex(var).assign(value);
                                count++;
                            }
                        }
                        break;
                }
            }
            return count;
        } else
            return 0;
    }

    public static boolean defined(Environment env, String name){
        Memory value = env.findConstant(name);
        return value != null;
    }

    public static boolean define(Environment env, TraceInfo trace, String name, Memory value, boolean caseInSentisise){
        return env.defineConstant(name, value, !caseInSentisise);
    }

    public static boolean define(Environment env, TraceInfo trace, String name, Memory value){
        return define(env, trace, name, value, false);
    }

    public static Memory constant(Environment env, TraceInfo trace, String name){
        int pos;
        if ((pos = name.indexOf("::")) > -1){
            String className = name.substring(0, pos);
            String constName = name.substring(pos + 2);
            ClassEntity entity = env.fetchClass(className, true);
            if (entity == null)
                return Memory.NULL;

            ConstantEntity constant = entity.findConstant(constName);
            return constant == null ? Memory.NULL : constant.getValue();
        } else {
            Memory value = env.findConstant(name);
            if (value == null)
                return Memory.NULL;
            else
                return value;
        }
    }

    @Runtime.Immutable
    public static String gettype(Memory memory){
        switch (memory.getRealType()){
            case ARRAY: return "array";
            case BOOL: return "boolean";
            case INT: return "integer";
            case DOUBLE: return "double";
            case STRING: return "string";
            case OBJECT:
                if (memory.isResource())
                    return "resource";

                return "object";
            case NULL: return "NULL";
            default:
                return "unknown type";
        }
    }

    public static boolean is_array(@Runtime.Reference Memory memory){
        return memory.isArray();
    }

    @Runtime.Immutable
    public static boolean is_bool(Memory memory){
        return memory.toValue().type == Memory.Type.BOOL;
    }

    @Runtime.Immutable
    public static boolean is_double(Memory memory){
        return memory.toValue().type == Memory.Type.DOUBLE;
    }

    @Runtime.Immutable
    public static boolean is_float(Memory memory){
        return memory.toValue().type == Memory.Type.DOUBLE;
    }

    @Runtime.Immutable
    public static boolean is_int(Memory memory){
        return memory.toValue().type == Memory.Type.INT;
    }

    @Runtime.Immutable
    public static boolean is_integer(Memory memory){
        return memory.toValue().type == Memory.Type.INT;
    }

    @Runtime.Immutable
    public static boolean is_long(Memory memory){
        return memory.toValue().type == Memory.Type.INT;
    }

    @Runtime.Immutable
    public static boolean is_null(Memory memory){
        return memory.isNull();
    }

    public static boolean is_object(@Runtime.Reference Memory memory){
        return memory.isObject();
    }

    @Runtime.Immutable
    public static boolean is_real(Memory memory){
        return is_float(memory);
    }

    @Runtime.Immutable
    public static boolean is_string(Memory memory){
        return memory.isString();
    }

    @Runtime.Immutable
    public static boolean is_scalar(Memory memory){
        switch (memory.getRealType()){
            case BOOL:
            case NULL:
            case INT:
            case DOUBLE:
            case STRING:
                return true;
        }
        return false;
    }

    public static boolean is_resource(@Runtime.Reference Memory memory){
        return memory.isResource();
    }

    public static Memory get_resource_type(@Runtime.Reference Memory memory){
        if (memory.isObject()){
            if (((ObjectMemory)memory).value instanceof Resource)
                return new StringMemory(
                        ((Resource) ((ObjectMemory)memory).value).getResourceType()
                );
        }
        return Memory.NULL;
    }

    public static boolean is_callable(Environment env, TraceInfo trace, @Runtime.Reference Memory memory)
            throws InvocationTargetException, IllegalAccessException {
        // optimize
        if (memory.isObject() && memory.toValue(ObjectMemory.class).value instanceof Closure)
            return true;
        Invoker invoker = Invoker.valueOf(env, null, memory);
        return invoker != null && invoker.canAccess(env, false) == 0;
    }

    @Runtime.Immutable
    public static boolean boolval(Memory memory){
        return memory.toBoolean();
    }

    @Runtime.Immutable
    public static String strval(Memory memory){
        return memory.toString();
    }

    @Runtime.Immutable
    public static long intval(Memory memory){
        return memory.toLong();
    }

    @Runtime.Immutable
    public static double floatval(Memory memory){
        return memory.toDouble();
    }

    @Runtime.Immutable
    public static double doubleval(Memory memory){
        return memory.toDouble();
    }

    public static boolean settype(@Runtime.Reference Memory memory, String type){
        if (memory.isReference()){
            if ("string".equals(type)){
                memory.assign(memory.toString());
            } else if ("bool".equals(type) || "boolean".equals(type)){
                memory.assign(memory.toBoolean());
            } else if ("int".equals(type) || "integer".equals(type)){
                memory.assign(memory.toLong());
            } else if ("float".equals(type) || "double".equals(type)){
                memory.assign(memory.toDouble());
            } else if ("null".equals(type)){
                memory.assign(Memory.NULL);
            } else
                return false;

            return true;
        }
        return false;
    }

    public void debug_zval_dump(Environment env, TraceInfo trace){
        env.warning(trace, "debug_zval_dump(): unsupported");
    }

    public static Memory func_get_args(Environment env, TraceInfo trace){
        if (env.getCallStackTop() == 0){
            return Memory.FALSE;
        }

        return new ArrayMemory(true, env.peekCall(0).args).toConstant();
    }

    public static Memory func_num_args(Environment env, TraceInfo trace){
        if (env.getCallStackTop() == 0){
            return Memory.FALSE;
        }

        return LongMemory.valueOf(env.peekCall(0).args.length);
    }

    public static Memory func_get_arg(Environment env, TraceInfo trace, int argNum){
        if (env.getCallStackTop() == 0){
            return Memory.FALSE;
        }
        if (argNum < 0)
            return Memory.FALSE;

        Memory[] args = env.peekCall(0).args;
        if (argNum < args.length)
            return args[argNum];
        else
            return Memory.FALSE;
    }

    private static Memory _call_user_func(Environment env, TraceInfo trace, Memory function, Memory... args)
            throws Throwable {
        Invoker invoker = expectingCallback(env, trace, 1, function);
        if (invoker == null){
            return Memory.FALSE;
        }
        invoker.setTrace(trace);

        invoker.pushCall(null, args);
        Memory result = Memory.FALSE;

        try {
            result = invoker.call(args);
        } finally {
            invoker.popCall();
        }
        return result;
    }

    public static Memory call_user_func(Environment env, TraceInfo trace, Memory function, Memory... args)
            throws Throwable {
        Memory[] passed;
        if (args == null){
            passed = new Memory[]{function};
        } else {
            passed = new Memory[args.length + 1];
            System.arraycopy(args, 0, passed, 1, args.length);
            passed[0] = function;
        }

        env.pushCall(trace, null, passed, "call_user_func", null, null);
        try {
            return _call_user_func(env, trace, function, args);
        } finally {
            env.popCall();
        }
    }

    public static Memory call_user_func_array(Environment env, TraceInfo trace, Memory function, Memory args)
            throws Throwable {
        if (expecting(env, trace, 2, args, Memory.Type.ARRAY)){
            Memory[] passed = new Memory[]{function, args};
            env.pushCall(trace, null, passed, "call_user_func_array", null, null);
            try {
                return call_user_func(env, trace, function, ((ArrayMemory) args).values(true));
            } finally {
                env.popCall();
            }
        }
        return Memory.FALSE;
    }

    public static Memory debug_backtrace(Environment env, TraceInfo trace, int options, int limit){
        boolean provideObject = (options & constants.DEBUG_BACKTRACE_PROVIDE_OBJECT)
                == constants.DEBUG_BACKTRACE_PROVIDE_OBJECT;
        boolean ignoreArgs = (options & constants.DEBUG_BACKTRACE_IGNORE_ARGS)
                == constants.DEBUG_BACKTRACE_IGNORE_ARGS;

        ArrayMemory result = new ArrayMemory();
        for(int i = 0; i < env.getCallStackTop(); i++){
            if (limit != 0 && i >= limit)
                break;

            CallStackItem item = env.peekCall(i);
            ArrayMemory el = item.toArray(provideObject, ignoreArgs);
            result.add(el);
        }

        return result.toConstant();
    }

    public static Memory debug_backtrace(Environment env, TraceInfo trace, int options){
        return debug_backtrace(env, trace, options, 0);
    }

    public static Memory debug_backtrace(Environment env, TraceInfo trace){
        return debug_backtrace(env, trace, constants.DEBUG_BACKTRACE_PROVIDE_OBJECT, 0);
    }


    public static void debug_print_backtrace(Environment env, TraceInfo trace, int options, int limit){
        boolean provideObject = (options & constants.DEBUG_BACKTRACE_PROVIDE_OBJECT)
                == constants.DEBUG_BACKTRACE_PROVIDE_OBJECT;
        boolean ignoreArgs = (options & constants.DEBUG_BACKTRACE_IGNORE_ARGS)
                == constants.DEBUG_BACKTRACE_IGNORE_ARGS;

        StackTracer stackTracer = new StackTracer(env, limit);
        env.echo(stackTracer.toString(!ignoreArgs));
    }

    public static void debug_print_backtrace(Environment env, TraceInfo trace, int options){
        debug_print_backtrace(env, trace, options, 0);
    }

    public static void debug_print_backtrace(Environment env, TraceInfo trace){
        debug_print_backtrace(env, trace, constants.DEBUG_BACKTRACE_PROVIDE_OBJECT, 0);
    }

    public static boolean function_exists(Environment env, String name){
        name = name.toLowerCase();
        FunctionEntity function = env.scope.findUserFunction(name);
        return function != null && (function.isInternal() || env.isLoadedFunction(name));
    }

    public static boolean method_exists(Environment env, Memory clazz, String method){
        ClassEntity classEntity;
        if (clazz.isObject()){
            ObjectMemory tmp = clazz.toValue(ObjectMemory.class);
            classEntity = tmp.getReflection();
        } else {
            String name = clazz.toString();
            String nameL = name.toLowerCase();
            classEntity = env.fetchClass(name, nameL, true);
            if (classEntity == null)
                classEntity = env.fetchMagicClass(name, nameL);
        }

        return classEntity.findMethod(method.toLowerCase()) != null;
    }

    public static Memory property_exists(Environment env, Memory clazz, String property) throws Throwable {
        ClassEntity classEntity;
        IObject object = null;
        boolean isMagic = false;
        if (clazz.isObject()){
            ObjectMemory tmp = clazz.toValue(ObjectMemory.class);
            classEntity = tmp.getReflection();
            object = tmp.value;
        } else {
            String name = clazz.toString();
            String nameL = name.toLowerCase();

            classEntity = env.fetchClass(name, nameL, true);
            if (classEntity == null) {
                classEntity = env.fetchMagicClass(name, nameL);
                isMagic = true;
            }
        }

        if (classEntity == null){
            return Memory.FALSE;
        }

        if (object != null){
            ArrayMemory props = object.getProperties();

            ClassEntity context = env.getLastClassOnStack();
            PropertyEntity entity = classEntity.isInstanceOf(context)
                    ? context.properties.get(property) : classEntity.properties.get(property);

            int accessFlags = entity == null ? 0 : entity.canAccess(env);
            if (accessFlags != 0)
                return Memory.FALSE;

            return (props != null && props.getByScalar(entity == null ? property : entity.getSpecificName()) != null)
                    ? Memory.TRUE : Memory.FALSE;
        } else {
            PropertyEntity entity = classEntity.properties.get(property);
            if (isMagic){
                int accessFlags = entity == null ? 0 : entity.canAccess(env);
                if (accessFlags != 0)
                    return Memory.FALSE;
            }

            return entity != null ? Memory.TRUE : Memory.FALSE;
        }
    }

    public static Memory is_subclass_of(Environment env, TraceInfo trace, Memory object, String className,
                                        boolean allowedString){
        ClassEntity classEntity = null;
        ClassEntity parentClass;

        if (allowedString && !object.isObject()){
            String name = object.toString();
            String nameL = name.toLowerCase();

            classEntity = env.fetchClass(name, nameL, true);
            if (classEntity == null)
                classEntity = env.fetchMagicClass(name, nameL);

        } else if (expecting(env, trace, 1, object, Memory.Type.OBJECT)){
            classEntity = object.toValue(ObjectMemory.class).getReflection();
        }
        parentClass = env.fetchClass(className, true);

        if (classEntity == null) {
            return Memory.NULL;
        } else if (parentClass == null) {
            return Memory.NULL;
        } else {
            if (object.isObject() && object.toValue(ObjectMemory.class).value instanceof Closure)
                return Memory.FALSE;
            do {
                classEntity = classEntity.getParent();
                if (classEntity == null)
                    break;
                if (classEntity.equals(parentClass))
                    return Memory.TRUE;

            } while (true);
            return Memory.FALSE;
        }
    }

    public static Memory is_subclass_of(Environment env, TraceInfo trace, Memory object, String className){
        return is_subclass_of(env, trace, object, className, true);
    }

    public static Memory get_class(Environment env, TraceInfo trace, Memory object){
        if (object.isNull()){
            CallStackItem item = env.peekCall(0);
            if (item.clazz != null){
                if (item.classEntity == null)
                    item.classEntity = env.fetchClass(item.clazz, false);

                if (item.classEntity == null)
                    return Memory.FALSE;
                else {
                    MethodEntity method = item.classEntity.findMethod(item.function);
                    if (method == null)
                        return Memory.FALSE;
                    return new StringMemory(method.getClazz().getName());
                }
            }
        } else if (expecting(env, trace, 1, object, Memory.Type.OBJECT)){
            return new StringMemory(object.toValue(ObjectMemory.class).getReflection().getName());
        }

        return Memory.FALSE;
    }

    public static Memory get_class(Environment env, TraceInfo trace){
        return get_class(env, trace, Memory.NULL);
    }

    public static Memory get_called_class(Environment env){
        String name = env.getLateStatic();
        return name == null || name.isEmpty() ? Memory.FALSE : new StringMemory(name);
    }

    public static Memory get_parent_class(Memory object){
        if (object.isObject()){
            ClassEntity classEntity = object.toValue(ObjectMemory.class).getReflection().getParent();
            if (classEntity == null)
                return Memory.NULL;
            else
                return new StringMemory(classEntity.getName());
        } else {
            return Memory.FALSE;
        }
    }

    public static Memory get_parent_class(Environment env){
        CallStackItem item = env.peekCall(0);
        if (item.clazz != null){
            if (item.classEntity == null)
                item.classEntity = env.fetchClass(item.clazz, false);

            if (item.classEntity == null)
                return Memory.FALSE;
            else {
                MethodEntity method = item.classEntity.findMethod(item.function);
                if (method == null)
                    return Memory.FALSE;

                ClassEntity parent = method.getClazz().getParent();
                return parent == null ? Memory.FALSE : new StringMemory(parent.getName());
            }
        }
        return Memory.FALSE;
    }
}
