package com.hubspot.jinjava.el;

import static com.hubspot.jinjava.util.Logging.ENGINE_LOG;

import com.google.common.collect.ImmutableMap;
import com.hubspot.jinjava.el.ext.AbstractCallableMethod;
import com.hubspot.jinjava.el.ext.DeferredParsingException;
import com.hubspot.jinjava.el.ext.ExtendedParser;
import com.hubspot.jinjava.el.ext.JinjavaBeanELResolver;
import com.hubspot.jinjava.el.ext.JinjavaListELResolver;
import com.hubspot.jinjava.el.ext.NamedParameter;
import com.hubspot.jinjava.interpret.DeferredValue;
import com.hubspot.jinjava.interpret.DeferredValueException;
import com.hubspot.jinjava.interpret.DisabledException;
import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import com.hubspot.jinjava.interpret.LazyExpression;
import com.hubspot.jinjava.interpret.TemplateError;
import com.hubspot.jinjava.interpret.TemplateError.ErrorItem;
import com.hubspot.jinjava.interpret.TemplateError.ErrorReason;
import com.hubspot.jinjava.interpret.TemplateError.ErrorType;
import com.hubspot.jinjava.interpret.errorcategory.BasicTemplateErrorCategory;
import com.hubspot.jinjava.objects.Namespace;
import com.hubspot.jinjava.objects.PyWrapper;
import com.hubspot.jinjava.objects.collections.SizeLimitingPyList;
import com.hubspot.jinjava.objects.collections.SizeLimitingPyMap;
import com.hubspot.jinjava.objects.date.FormattedDate;
import com.hubspot.jinjava.objects.date.PyishDate;
import com.hubspot.jinjava.objects.date.StrftimeFormatter;
import de.odysseus.el.util.SimpleResolver;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.el.ArrayELResolver;
import javax.el.CompositeELResolver;
import javax.el.ELContext;
import javax.el.ELResolver;
import javax.el.PropertyNotFoundException;
import javax.el.ResourceBundleELResolver;
import org.apache.commons.lang3.LocaleUtils;
import org.apache.commons.lang3.StringUtils;

public class JinjavaInterpreterResolver extends SimpleResolver {
  public static final ELResolver DEFAULT_RESOLVER_READ_ONLY = new CompositeELResolver() {

    {
      add(new ArrayELResolver(true));
      add(new JinjavaListELResolver(true));
      add(new TypeConvertingMapELResolver(true));
      add(new ResourceBundleELResolver());
      add(new JinjavaBeanELResolver(true));
    }
  };

  public static final ELResolver DEFAULT_RESOLVER_READ_WRITE = new CompositeELResolver() {

    {
      add(new ArrayELResolver(false));
      add(new JinjavaListELResolver(false));
      add(new TypeConvertingMapELResolver(false));
      add(new ResourceBundleELResolver());
      add(new JinjavaBeanELResolver(false));
    }
  };

  private final JinjavaInterpreter interpreter;

  public JinjavaInterpreterResolver(JinjavaInterpreter interpreter) {
    super(interpreter.getConfig().getElResolver());
    this.interpreter = interpreter;
  }

  @Override
  public Object invoke(
    ELContext context,
    Object base,
    Object method,
    Class<?>[] paramTypes,
    Object[] params
  ) {
    try {
      Object methodProperty = getValue(context, base, method, false);
      if (methodProperty instanceof AbstractCallableMethod) {
        context.setPropertyResolved(true);
        return interpreter.getContext().isValidationMode()
          ? ""
          : ((AbstractCallableMethod) methodProperty).evaluate(params);
      }
    } catch (IllegalArgumentException e) {
      // failed to access property, continue with method calls
    }

    return interpreter.getContext().isValidationMode()
      ? ""
      : super.invoke(
        context,
        base,
        method,
        paramTypes,
        generateMethodParams(method, params)
      );
  }

  /**
   * {@inheritDoc}
   *
   * If the base object is null, the property will be looked up in the context.
   */
  @Override
  public Object getValue(ELContext context, Object base, Object property) {
    return getValue(context, base, property, true);
  }

  /*
   * We transform the AST parameters to something meaningful to Jinjava.
   *
   * Functions, expressions and tags will receive the parameters as they are, but filters
   * have a different signature to what they have in the AST to support named parameters, so
   * this method transforms their arguments to be the following:
   *
   *  (Left Value, JinjavaInterpreter, Positional Arguments, Named Arguments)
   */
  private Object[] generateMethodParams(Object method, Object[] astParams) {
    if (!"filter".equals(method)) {
      return astParams; // We only change the signature method for filters
    }

    List<Object> args = new ArrayList<>();
    Map<String, Object> kwargs = new LinkedHashMap<>();

    // 2 -> Ignore the Left Value (0) and the JinjavaInterpreter (1)
    for (Object param : Arrays.asList(astParams).subList(2, astParams.length)) {
      if (param instanceof NamedParameter) {
        NamedParameter namedParameter = (NamedParameter) param;
        kwargs.put(namedParameter.getName(), namedParameter.getValue());
      } else {
        args.add(param);
      }
    }

    return new Object[] { astParams[0], astParams[1], args.toArray(), kwargs };
  }

  private Object getValue(
    ELContext context,
    Object base,
    Object property,
    boolean errOnUnknownProp
  ) {
    String propertyName = Objects.toString(property, "");
    Object value = null;

    interpreter.getContext().addResolvedValue(propertyName);
    ErrorItem item = ErrorItem.PROPERTY;

    try {
      if (ExtendedParser.INTERPRETER.equals(property)) {
        value = interpreter;
      } else if (propertyName.startsWith(ExtendedParser.FILTER_PREFIX)) {
        item = ErrorItem.FILTER;
        value =
          interpreter
            .getContext()
            .getFilter(
              StringUtils.substringAfter(propertyName, ExtendedParser.FILTER_PREFIX)
            );
      } else if (propertyName.startsWith(ExtendedParser.EXPTEST_PREFIX)) {
        item = ErrorItem.EXPRESSION_TEST;
        value =
          interpreter
            .getContext()
            .getExpTest(
              StringUtils.substringAfter(propertyName, ExtendedParser.EXPTEST_PREFIX)
            );
      } else {
        if (base == null) {
          // Look up property in context.
          value =
            interpreter.retraceVariable(
              (String) property,
              interpreter.getLineNumber(),
              -1
            );
        } else {
          // Get property of base object.
          try {
            if (base instanceof Optional) {
              Optional<?> optBase = (Optional<?>) base;
              if (!optBase.isPresent()) {
                return null;
              }

              base = optBase.get();
            }

            if (base instanceof LazyExpression) {
              base = ((LazyExpression) base).get();
              if (base == null) {
                return null;
              }
            }

            // java doesn't natively support negative array indices, so the
            // super class getValue returns null for them.  To make negative
            // indices work as they do in python, detect them here and convert
            // to the equivalent positive index.
            //
            // Check for Integer or Long instead of Number so the behavior for a
            // floating-point index doesn't change (e.g. -1.5 stays -1.5, it
            // doesn't become -1).
            if (
              base.getClass().isArray() &&
              ((property instanceof Integer) || (property instanceof Long))
            ) {
              int propertyNum = ((Number) property).intValue();
              if (propertyNum < 0) {
                propertyNum += ((Object[]) base).length;
                propertyName = String.valueOf(propertyNum);
              }
            }

            value = super.getValue(context, base, propertyName);

            if (value instanceof Optional) {
              Optional<?> optValue = (Optional<?>) value;
              if (!optValue.isPresent()) {
                return null;
              }

              value = optValue.get();
            }

            if (value instanceof LazyExpression) {
              value = ((LazyExpression) value).get();
              if (value == null) {
                return null;
              }
            }

            if (value instanceof DeferredValue) {
              if (interpreter.getConfig().getExecutionMode().useEagerParser()) {
                throw new DeferredParsingException(this, propertyName);
              } else {
                throw new DeferredValueException(
                  propertyName,
                  interpreter.getLineNumber(),
                  interpreter.getPosition()
                );
              }
            }
          } catch (PropertyNotFoundException e) {
            if (errOnUnknownProp) {
              interpreter.addError(
                TemplateError.fromUnknownProperty(
                  base,
                  propertyName,
                  interpreter.getLineNumber(),
                  -1
                )
              );
            }
          }
        }
      }
    } catch (DisabledException e) {
      interpreter.addError(
        new TemplateError(
          ErrorType.FATAL,
          ErrorReason.DISABLED,
          item,
          e.getMessage(),
          propertyName,
          interpreter.getLineNumber(),
          -1,
          e
        )
      );
    }

    context.setPropertyResolved(true);
    return wrap(value);
  }

  @SuppressWarnings("unchecked")
  Object wrap(Object value) {
    if (value == null) {
      return null;
    }

    if (value instanceof LazyExpression) {
      value = ((LazyExpression) value).get();
      if (value == null) {
        return null;
      }
    }

    if (value instanceof PyWrapper) {
      return value;
    }

    if (value instanceof Namespace) {
      return new SizeLimitingPyMap(
        (Namespace) value,
        interpreter.getConfig().getMaxMapSize()
      );
    }

    if (List.class.isAssignableFrom(value.getClass())) {
      return new SizeLimitingPyList(
        (List<Object>) value,
        interpreter.getConfig().getMaxListSize()
      );
    }
    if (Map.class.isAssignableFrom(value.getClass())) {
      // FIXME: ensure keys are actually strings, if not, convert them
      return new SizeLimitingPyMap(
        (Map<String, Object>) value,
        interpreter.getConfig().getMaxMapSize()
      );
    }

    if (Date.class.isAssignableFrom(value.getClass())) {
      return new PyishDate(
        localizeDateTime(
          interpreter,
          ZonedDateTime.ofInstant(
            Instant.ofEpochMilli(((Date) value).getTime()),
            ZoneOffset.UTC
          )
        )
      );
    }
    if (ZonedDateTime.class.isAssignableFrom(value.getClass())) {
      return new PyishDate(localizeDateTime(interpreter, (ZonedDateTime) value));
    }

    if (FormattedDate.class.isAssignableFrom(value.getClass())) {
      return formattedDateToString(interpreter, (FormattedDate) value);
    }

    return value;
  }

  private static ZonedDateTime localizeDateTime(
    JinjavaInterpreter interpreter,
    ZonedDateTime dt
  ) {
    ENGINE_LOG.debug(
      "Using timezone: {} to localize datetime: {}",
      interpreter.getConfig().getTimeZone(),
      dt
    );
    return dt.withZoneSameInstant(interpreter.getConfig().getTimeZone());
  }

  private static String formattedDateToString(
    JinjavaInterpreter interpreter,
    FormattedDate d
  ) {
    DateTimeFormatter formatter = getFormatter(interpreter, d)
      .withLocale(getLocale(interpreter, d));
    return formatter.format(localizeDateTime(interpreter, d.getDate()));
  }

  private static DateTimeFormatter getFormatter(
    JinjavaInterpreter interpreter,
    FormattedDate d
  ) {
    if (!StringUtils.isBlank(d.getFormat())) {
      try {
        return StrftimeFormatter.formatter(
          d.getFormat(),
          interpreter.getConfig().getLocale()
        );
      } catch (IllegalArgumentException e) {
        interpreter.addError(
          new TemplateError(
            ErrorType.WARNING,
            ErrorReason.SYNTAX_ERROR,
            ErrorItem.OTHER,
            e.getMessage(),
            null,
            interpreter.getLineNumber(),
            -1,
            null,
            BasicTemplateErrorCategory.UNKNOWN_DATE,
            ImmutableMap.of(
              "date",
              d.getDate().toString(),
              "exception",
              e.getMessage(),
              "lineNumber",
              String.valueOf(interpreter.getLineNumber())
            )
          )
        );
      }
    }

    return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM);
  }

  private static Locale getLocale(JinjavaInterpreter interpreter, FormattedDate d) {
    if (!StringUtils.isBlank(d.getLanguage())) {
      try {
        return LocaleUtils.toLocale(d.getLanguage());
      } catch (IllegalArgumentException e) {
        interpreter.addError(
          new TemplateError(
            ErrorType.WARNING,
            ErrorReason.SYNTAX_ERROR,
            ErrorItem.OTHER,
            e.getMessage(),
            null,
            interpreter.getLineNumber(),
            -1,
            null,
            BasicTemplateErrorCategory.UNKNOWN_LOCALE,
            ImmutableMap.of(
              "date",
              d.getDate().toString(),
              "exception",
              e.getMessage(),
              "lineNumber",
              String.valueOf(interpreter.getLineNumber())
            )
          )
        );
      }
    }

    return Locale.US;
  }
}
