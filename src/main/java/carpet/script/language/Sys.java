package carpet.script.language;

import carpet.script.Context;
import carpet.script.Expression;
import carpet.script.LazyValue;
import carpet.script.ScriptHost;
import carpet.script.argument.FunctionArgument;
import carpet.script.exception.InternalExpressionException;
import carpet.script.utils.PerlinNoiseSampler;
import carpet.script.utils.SimplexNoiseSampler;
import carpet.script.value.FunctionValue;
import carpet.script.value.ListValue;
import carpet.script.value.NullValue;
import carpet.script.value.NumericValue;
import carpet.script.value.StringValue;
import carpet.script.value.ThreadValue;
import carpet.script.value.Value;
import org.apache.commons.lang3.text.WordUtils;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.IllegalFormatException;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Sys {
    public static final Random randomizer = new Random();
    // %[argument_index$][flags][width][.precision][t]conversion
    private static final Pattern formatPattern = Pattern.compile("%(\\d+\\$)?([-#+ 0,(<]*)?(\\d+)?(\\.\\d+)?([tT])?([a-zA-Z%])");

    public static void apply(Expression expression)
    {
        expression.addUnaryFunction("hash_code", v -> new NumericValue(v.hashCode()));

        expression.addUnaryFunction("copy", Value::deepcopy);

        expression.addLazyFunction("bool", 1, (c, t, lv) -> {
            Value v = lv.get(0).evalValue(c, Context.BOOLEAN);
            if (v instanceof StringValue)
            {
                String str = v.getString();
                if ("false".equalsIgnoreCase(str) || "null".equalsIgnoreCase(str))
                {
                    return (cc, tt) -> Value.FALSE;
                }
            }
            Value retval = new NumericValue(v.getBoolean());
            return (cc, tt) -> retval;
        });
        expression.addUnaryFunction("number", v -> {
            if (v instanceof NumericValue)
            {
                return v;
            }
            try
            {
                return new NumericValue(v.getString());
            }
            catch (NumberFormatException format)
            {
                return Value.NULL;
            }
        });
        expression.addFunction("str", lv ->
        {
            if (lv.size() == 0)
                throw new InternalExpressionException("'str' requires at least one argument");
            String format = lv.get(0).getString();
            if (lv.size() == 1)
                return new StringValue(format);
            int argIndex = 1;
            if (lv.get(1) instanceof ListValue && lv.size() == 2)
            {
                lv = ((ListValue) lv.get(1)).getItems();
                argIndex = 0;
            }
            List<Object> args = new ArrayList<>();
            Matcher m = formatPattern.matcher(format);

            for (int i = 0, len = format.length(); i < len; ) {
                if (m.find(i)) {
                    // Anything between the start of the string and the beginning
                    // of the format specifier is either fixed text or contains
                    // an invalid format string.
                    // [[scarpet]] but we skip it and let the String.format fail
                    char fmt = m.group(6).toLowerCase().charAt(0);
                    if (fmt == 's')
                    {
                        if (argIndex >= lv.size())
                            throw new InternalExpressionException("Not enough arguments for "+m.group(0));
                        args.add(lv.get(argIndex).getString());
                        argIndex++;
                    }
                    else if (fmt == 'd' || fmt == 'o' || fmt == 'x')
                    {
                        if (argIndex >= lv.size())
                            throw new InternalExpressionException("Not enough arguments for "+m.group(0));
                        args.add(lv.get(argIndex).readInteger());
                        argIndex++;
                    }
                    else if (fmt == 'a' || fmt == 'e' || fmt == 'f' || fmt == 'g')
                    {
                        if (argIndex >= lv.size())
                            throw new InternalExpressionException("Not enough arguments for "+m.group(0));
                        args.add(lv.get(argIndex).readDoubleNumber());
                        argIndex++;
                    }
                    else if (fmt == 'b')
                    {
                        if (argIndex >= lv.size())
                            throw new InternalExpressionException("Not enough arguments for "+m.group(0));
                        args.add(lv.get(argIndex).getBoolean());
                        argIndex++;
                    }
                    else if (fmt == '%')
                    {
                        //skip /%%
                    }
                    else
                    {
                        throw new InternalExpressionException("Format not supported: "+m.group(6));
                    }

                    i = m.end();
                } else {
                    // No more valid format specifiers.  Check for possible invalid
                    // format specifiers.
                    // [[scarpet]] but we skip it and let the String.format fail
                    break;
                }
            }
            try
            {
                return new StringValue(String.format(Locale.ROOT, format, args.toArray()));
            }
            catch (IllegalFormatException ife)
            {
                throw new InternalExpressionException("Illegal string format: "+ife.getMessage());
            }
        });

        expression.addUnaryFunction("lower", v -> new StringValue(v.getString().toLowerCase(Locale.ROOT)));

        expression.addUnaryFunction("upper", v -> new StringValue(v.getString().toUpperCase(Locale.ROOT)));

        expression.addUnaryFunction("title", v -> new StringValue(WordUtils.capitalizeFully(v.getString())));

        expression.addFunction("replace", (lv) ->
        {
            if (lv.size() != 3 && lv.size() !=2)
                throw new InternalExpressionException("'replace' expects string to read, pattern regex, and optional replacement string");
            String data = lv.get(0).getString();
            String regex = lv.get(1).getString();
            String replacement = "";
            if (lv.size() == 3)
                replacement = lv.get(2).getString();
            return new StringValue(data.replaceAll(regex, replacement));
        });

        expression.addFunction("replace_first", (lv) ->
        {
            if (lv.size() != 3 && lv.size() !=2)
                throw new InternalExpressionException("'replace_first' expects string to read, pattern regex, and optional replacement string");
            String data = lv.get(0).getString();
            String regex = lv.get(1).getString();
            String replacement = "";
            if (lv.size() == 3)
                replacement = lv.get(2).getString();
            return new StringValue(data.replaceFirst(regex, replacement));
        });

        expression.addUnaryFunction("type", v -> new StringValue(v.getTypeString()));

        expression.addUnaryFunction("length", v -> new NumericValue(v.length()));
        expression.addLazyFunction("rand", -1, (c, t, lv) -> {
            int argsize = lv.size();
            Random randomizer = Sys.randomizer;
            if (argsize != 1 && argsize != 2)
                throw new InternalExpressionException("'rand' takes one (range) or two arguments (range and seed)");
            if (argsize == 2) randomizer = c.host.getRandom(NumericValue.asNumber(lv.get(1).evalValue(c)).getLong());
            Value argument = lv.get(0).evalValue(c);
            if (argument instanceof ListValue)
            {
                List<Value> list = ((ListValue) argument).getItems();
                Value retval = list.get(randomizer.nextInt(list.size()));
                return (cc, tt) -> retval;
            }
            if (t == Context.BOOLEAN)
            {
                double rv = NumericValue.asNumber(argument).getDouble()*randomizer.nextFloat();
                Value retval = rv<1.0D?Value.FALSE:Value.TRUE;
                return (cc, tt) -> retval;
            }
            Value retval = new NumericValue(NumericValue.asNumber(argument).getDouble()*randomizer.nextDouble());
            return (cc, tt) -> retval;
        });

        expression.addLazyFunction("perlin", -1, (c, t, lv) -> {
            PerlinNoiseSampler sampler;
            Value x, y, z;

            if (lv.size() >= 4)
            {
                x = lv.get(0).evalValue(c);
                y = lv.get(1).evalValue(c);
                z = lv.get(2).evalValue(c);
                sampler = PerlinNoiseSampler.getPerlin(NumericValue.asNumber(lv.get(3).evalValue(c)).getLong());
            }
            else
            {
                sampler = PerlinNoiseSampler.instance;
                y = Value.NULL;
                z = Value.NULL;
                if (lv.size() == 0 )
                    throw new InternalExpressionException("'perlin' requires at least one dimension to sample from");
                x = NumericValue.asNumber(lv.get(0).evalValue(c));
                if (lv.size() > 1)
                {
                    y = NumericValue.asNumber(lv.get(1).evalValue(c));
                    if (lv.size() > 2)
                        z = NumericValue.asNumber(lv.get(2).evalValue(c));
                }
            }

            double result;

            if (z instanceof NullValue)
                if (y instanceof NullValue)
                    result = sampler.sample1d(NumericValue.asNumber(x).getDouble());
                else
                    result = sampler.sample2d(NumericValue.asNumber(x).getDouble(), NumericValue.asNumber(y).getDouble());
            else
                result = sampler.sample3d(
                        NumericValue.asNumber(x).getDouble(),
                        NumericValue.asNumber(y).getDouble(),
                        NumericValue.asNumber(z).getDouble());
            Value ret = new NumericValue(result);
            return (cc, tt) -> ret;
        });

        expression.addLazyFunction("simplex", -1, (c, t, lv) -> {
            SimplexNoiseSampler sampler;
            Value x, y, z;

            if (lv.size() >= 4)
            {
                x = lv.get(0).evalValue(c);
                y = lv.get(1).evalValue(c);
                z = lv.get(2).evalValue(c);
                sampler = SimplexNoiseSampler.getSimplex(NumericValue.asNumber(lv.get(3).evalValue(c)).getLong());
            }
            else
            {
                sampler = SimplexNoiseSampler.instance;
                z = Value.NULL;
                if (lv.size() < 2 )
                    throw new InternalExpressionException("'simplex' requires at least two dimensions to sample from");
                x = NumericValue.asNumber(lv.get(0).evalValue(c));
                y = NumericValue.asNumber(lv.get(1).evalValue(c));
                if (lv.size() > 2)
                    z = NumericValue.asNumber(lv.get(2).evalValue(c));
            }
            double result;

            if (z instanceof NullValue)
                result = sampler.sample2d(NumericValue.asNumber(x).getDouble(), NumericValue.asNumber(y).getDouble());
            else
                result = sampler.sample3d(
                        NumericValue.asNumber(x).getDouble(),
                        NumericValue.asNumber(y).getDouble(),
                        NumericValue.asNumber(z).getDouble());
            Value ret = new NumericValue(result);
            return (cc, tt) -> ret;
        });

        expression.addUnaryFunction("print", (v) ->
        {
            System.out.println(v.getString());
            return v; // pass through for variables
        });
        expression.addUnaryFunction("sleep", (v) ->
        {
            long time = NumericValue.asNumber(v).getLong();
            try
            {
                Thread.sleep(time);
                Thread.yield();
            }
            catch (InterruptedException ignored) { }
            return v; // pass through for variables
        });
        expression.addLazyFunction("time", 0, (c, t, lv) ->
        {
            Value time = new NumericValue((System.nanoTime() / 1000) / 1000.0);
            return (cc, tt) -> time;
        });

        expression.addLazyFunction("unix_time", 0, (c, t, lv) ->
        {
            Value time = new NumericValue(System.currentTimeMillis());
            return (cc, tt) -> time;
        });

        expression.addFunction("convert_date", lv ->
        {
            int argsize = lv.size();
            if (lv.size() == 0) throw new InternalExpressionException("'convert_date' requires at least one parameter");
            Value value = lv.get(0);
            if (argsize == 1 && !(value instanceof ListValue))
            {
                Calendar cal = new GregorianCalendar(Locale.ROOT);
                cal.setTimeInMillis(NumericValue.asNumber(value, "timestamp").getLong());
                int weekday = cal.get(Calendar.DAY_OF_WEEK)-1;
                if (weekday == 0) weekday = 7;
                Value retVal = ListValue.ofNums(
                        cal.get(Calendar.YEAR),
                        cal.get(Calendar.MONTH)+1,
                        cal.get(Calendar.DAY_OF_MONTH),
                        cal.get(Calendar.HOUR_OF_DAY),
                        cal.get(Calendar.MINUTE),
                        cal.get(Calendar.SECOND),
                        weekday,
                        cal.get(Calendar.DAY_OF_YEAR),
                        cal.get(Calendar.WEEK_OF_YEAR)
                );
                return retVal;
            }
            else if(value instanceof ListValue)
            {
                lv = ((ListValue) value).getItems();
                argsize = lv.size();
            }
            Calendar cal = new GregorianCalendar(0, 0, 0, 0, 0, 0);

            if (argsize == 3)
            {
                cal.set(
                        NumericValue.asNumber(lv.get(0)).getInt(),
                        NumericValue.asNumber(lv.get(1)).getInt()-1,
                        NumericValue.asNumber(lv.get(2)).getInt()
                );
            }
            else if (argsize == 6)
            {
                cal.set(
                        NumericValue.asNumber(lv.get(0)).getInt(),
                        NumericValue.asNumber(lv.get(1)).getInt()-1,
                        NumericValue.asNumber(lv.get(2)).getInt(),
                        NumericValue.asNumber(lv.get(3)).getInt(),
                        NumericValue.asNumber(lv.get(4)).getInt(),
                        NumericValue.asNumber(lv.get(5)).getInt()
                );
            }
            else throw new InternalExpressionException("Date conversion requires 3 arguments for Dates or 6 arguments, for time");
            return new NumericValue(cal.getTimeInMillis());
        });

        expression.addLazyFunction("profile_expr", 1, (c, t, lv) ->
        {
            LazyValue lazy = lv.get(0);
            long end = System.nanoTime()+50000000L;
            long it = 0;
            while (System.nanoTime()<end)
            {
                lazy.evalValue(c);
                it++;
            }
            Value res = new NumericValue(it);
            return (cc, tt) -> res;
        });

        expression.addLazyFunction("var", 1, (c, t, lv) -> {
            String varname = lv.get(0).evalValue(c).getString();
            return expression.getOrSetAnyVariable(c, varname);
        });

        expression.addLazyFunction("undef", 1, (c, t, lv) ->
        {
            Value remove = lv.get(0).evalValue(c);
            if (remove instanceof FunctionValue)
            {
                c.host.delFunction(expression.module, remove.getString());
                return (cc, tt) -> Value.NULL;
            }
            String varname = remove.getString();
            boolean isPrefix = varname.endsWith("*");
            if (isPrefix)
                varname = varname.replaceAll("\\*+$", "");
            if (isPrefix)
            {
                c.host.delFunctionWithPrefix(expression.module, varname);
                if (varname.startsWith("global_"))
                {
                    c.host.delGlobalVariableWithPrefix(expression.module, varname);
                }
                else if (!varname.startsWith("_"))
                {
                    c.removeVariablesMatching(varname);
                }
            }
            else
            {
                c.host.delFunction(expression.module, varname);
                if (varname.startsWith("global_"))
                {
                    c.host.delGlobalVariable(expression.module, varname);
                }
                else if (!varname.startsWith("_"))
                {
                    c.delVariable(varname);
                }
            }
            return (cc, tt) -> Value.NULL;
        });

        //deprecate
        expression.addLazyFunction("vars", 1, (c, t, lv) -> {
            String prefix = lv.get(0).evalValue(c).getString();
            List<Value> values = new ArrayList<>();
            if (prefix.startsWith("global"))
            {
                c.host.globaVariableNames(expression.module, (s) -> s.startsWith(prefix)).forEach(s -> values.add(new StringValue(s)));
            }
            else
            {
                c.getAllVariableNames().stream().filter(s -> s.startsWith(prefix)).forEach(s -> values.add(new StringValue(s)));
            }
            Value retval = ListValue.wrap(values);
            return (cc, tt) -> retval;
        });

        expression.addLazyFunctionWithDelegation("task", -1, (c, t, expr, tok, lv) -> {
            if (lv.size() == 0)
                throw new InternalExpressionException("'task' requires at least function to call as a parameter");
            FunctionArgument functionArgument = FunctionArgument.findIn(c, expression.module, lv, 0, true, false);
            Value queue = Value.NULL;
            if (lv.size() > functionArgument.offset) queue = lv.get(functionArgument.offset).evalValue(c);
            ThreadValue thread = new ThreadValue(queue, functionArgument.function, expr, tok, c, functionArgument.args);
            Thread.yield();
            return (cc, tt) -> thread;
        });

        expression.addFunction("task_count", (lv) ->
        {
            if (lv.size() > 0)
            {
                return new NumericValue(ThreadValue.taskCount(lv.get(0)));
            }
            return new NumericValue(ThreadValue.taskCount());
        });

        expression.addUnaryFunction("task_value", (v) -> {
            if (!(v instanceof ThreadValue))
                throw new InternalExpressionException("'task_value' could only be used with a task value");
            return ((ThreadValue) v).getValue();
        });

        expression.addUnaryFunction("task_join", (v) -> {
            if (!(v instanceof ThreadValue))
                throw new InternalExpressionException("'task_join' could only be used with a task value");
            return ((ThreadValue) v).join();
        });

        expression.addLazyFunction("task_dock", 1, (c, t, lv) -> {
            // pass through placeholder
            // implmenetation should dock the task on the main thread.
            return lv.get(0);
        });

        expression.addUnaryFunction("task_completed", (v) -> {
            if (!(v instanceof ThreadValue))
                throw new InternalExpressionException("'task_completed' could only be used with a task value");
            return new NumericValue(((ThreadValue) v).isFinished());
        });

        expression.addLazyFunction("synchronize", -1, (c, t, lv) ->
        {
            if (lv.size() == 0) throw new InternalExpressionException("'synchronize' require at least an expression to synchronize");
            Value lockValue = Value.NULL;
            int ind = 0;
            if (lv.size() == 2)
            {
                lockValue = lv.get(0).evalValue(c);
                ind = 1;
            }
            synchronized (ThreadValue.getLock(lockValue))
            {
                Value ret = lv.get(ind).evalValue(c, t);
                return (_c, _t) -> ret;
            }
        });

        expression.addLazyFunction("system_variable_get", -1, (c, t, lv) ->
        {
            if (lv.size() == 0) throw new InternalExpressionException("'system_variable_get' expects at least a key to be fetched");
            Value key = lv.get(0).evalValue(c);
            if (lv.size() > 1)
            {
                ScriptHost.systemGlobals.computeIfAbsent(key, k -> lv.get(1).evalValue(c));
            }
            Value res = ScriptHost.systemGlobals.get(key);
            if (res!=null) return (cc, tt) -> res;
            return (cc, tt) -> Value.NULL;
        });

        expression.addLazyFunction("system_variable_set", 2, (c, t, lv) ->
        {
            Value key = lv.get(0).evalValue(c);
            Value value = lv.get(1).evalValue(c);
            Value res = ScriptHost.systemGlobals.put(key, value);
            if (res!=null) return (cc, tt) -> res;
            return (cc, tt) -> Value.NULL;
        });


    }
}
