package implementation;

import interpreter.*;

import ast.PrintScriptVersion;
import ast.Program;
import formatter.FormattingRules;
import interpreter.PrintScriptLinter;
import lexer.LexerConfigurations;
import lexer.StreamLexer;
import linter.config.IdentifierFormat;
import linter.config.LintConfig;
import org.json.JSONObject;
import org.json.JSONTokener;
import parser.ConfigurableParser;
import parser.grammar.GrammarConfigurations;
import result.CompilerError;
import result.Result;
import semantic.SemanticAnalyzer;
import semantic.SemanticConfigurations;
import token.Token;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;


public class CustomImplementationFactory implements PrintScriptFactory {

    @Override
    public PrintScriptInterpreter interpreter() {
        return (src, version, emitter, handler, provider) -> {
            try {
                PrintScriptVersion psVersion = PrintScriptVersion.Companion.fromString(version);
                Program program = loadProgram(src, psVersion, handler);
                if (program == null) return;

                // Adaptamos el PrintEmitter del TCK a tu interfaz Output
                Output output = emitter::print;

                // Adaptamos el InputProvider del TCK al tuyo
                interpreter.InputProvider myInputProvider = provider::input;

                var config = InterpreterConfigurations.INSTANCE.getConfiguration(psVersion);
                new ConfigurableInterpreter(output, config, myInputProvider, new SystemEnvProvider()).run(program);

            } catch (Exception e) {
                handler.reportError(e.getMessage() != null ? e.getMessage() : e.toString());
            }
        };
    }

    @Override
    public interpreter.PrintScriptFormatter formatter() {
        return (src, version, config, writer) -> {
            try {
                PrintScriptVersion psVersion = PrintScriptVersion.Companion.fromString(version);

                // El formatter ahora necesita los tokens (para preservar espaciado original
                // cuando una regla viene en null), así que tokenizamos y parseamos acá en vez
                // de reusar loadProgram (que solo devuelve el Program).
                Reader reader = new BufferedReader(new InputStreamReader(src, StandardCharsets.UTF_8));
                var lexerConfig = LexerConfigurations.INSTANCE.getConfiguration(psVersion);
                var tokenResult = new StreamLexer(reader, lexerConfig).tokenize();
                if (tokenResult instanceof Result.Failure<?>) return;
                @SuppressWarnings("unchecked")
                List<Token> tokens = (List<Token>) ((Result.Success<?>) tokenResult).getValue();

                var parserConfig = GrammarConfigurations.INSTANCE.getConfiguration(psVersion);
                var parseResult = new ConfigurableParser(parserConfig).parse(tokens);
                if (parseResult instanceof Result.Failure<?>) return;
                Program program = (Program) ((Result.Success<?>) parseResult).getValue();

                FormattingRules rules = parseFormattingRules(config);

                String formatted = new formatter.PrintScriptFormatter(rules).format(tokens, program);
                writer.write(formatted);
                writer.flush();

            } catch (Exception e) {
                // el formatter del TCK no tiene ErrorHandler, así que solo no escribimos o logueamos
                e.printStackTrace();
            }
        };
    }

    @Override
    public PrintScriptLinter linter() {
        return (src, version, config, handler) -> {
            try {
                PrintScriptVersion psVersion = PrintScriptVersion.Companion.fromString(version);
                Program program = loadProgram(src, psVersion, handler);
                if (program == null) return;

                LintConfig lintConfig = parseLintConfig(config);

                var notifications = new linter.PrintScriptLinter(lintConfig).lint(program);
                for (var n : notifications) {
                    handler.reportError(
                            n.getSeverity() + ": " +
                                    n.getPosition().getLine() + ":" + n.getPosition().getColumn() + " - " +
                                    n.getMessage()
                    );
                }

            } catch (Exception e) {
                handler.reportError(e.getMessage() != null ? e.getMessage() : e.toString());
            }
        };
    }

    /** Parsea el config.json (kebab-case) del TCK a FormattingRules */
    private FormattingRules parseFormattingRules(InputStream config) {
        JSONObject json = new JSONObject(new JSONTokener(new InputStreamReader(config, StandardCharsets.UTF_8)));

        boolean forceSingleSpace = json.optBoolean("mandatory-single-space-separation", false);

        Boolean spaceBeforeColon = forceSingleSpace ? Boolean.TRUE : null;
        if (json.has("enforce-spacing-before-colon-in-declaration")) {
            spaceBeforeColon = json.getBoolean("enforce-spacing-before-colon-in-declaration");
        }

        Boolean spaceAfterColon = forceSingleSpace ? Boolean.TRUE : null;
        if (json.has("enforce-spacing-after-colon-in-declaration")) {
            spaceAfterColon = json.getBoolean("enforce-spacing-after-colon-in-declaration");
        }

        Boolean spaceAroundEqual = null;
        if (json.optBoolean("enforce-spacing-around-equals", false) || forceSingleSpace) {
            spaceAroundEqual = Boolean.TRUE;
        } else if (json.optBoolean("enforce-no-spacing-around-equals", false)) {
            spaceAroundEqual = Boolean.FALSE;
        }

        Integer newlinesBeforePrintln = null;
        if (json.has("line-breaks-after-println")) {
            newlinesBeforePrintln = json.getInt("line-breaks-after-println");
        }

        Integer indentationSpaces = null;
        if (json.has("indent-inside-if")) {
            indentationSpaces = json.getInt("indent-inside-if");
        }

        Boolean spaceAroundPrintParens = forceSingleSpace ? Boolean.TRUE : null;

        Boolean ifBraceOnNewLine = null;
        if (json.optBoolean("if-brace-below-line", false)) {
            ifBraceOnNewLine = Boolean.TRUE;
        } else if (json.optBoolean("if-brace-same-line", false)) {
            ifBraceOnNewLine = Boolean.FALSE;
        }

        return new FormattingRules(
                spaceBeforeColon, spaceAfterColon, spaceAroundEqual, newlinesBeforePrintln,
                indentationSpaces, spaceAroundPrintParens, ifBraceOnNewLine);
    }

    /** Parsea el config.json (kebab-case) del TCK a LintConfig */
    private LintConfig parseLintConfig(InputStream config) {
        JSONObject json = new JSONObject(new JSONTokener(new InputStreamReader(config, StandardCharsets.UTF_8)));

        IdentifierFormat identifierFormat = IdentifierFormat.CAMEL_CASE;
        if (json.has("identifier_format")) {
            String value = json.getString("identifier_format");
            identifierFormat = "snake case".equalsIgnoreCase(value)
                    ? IdentifierFormat.SNAKE_CASE
                    : IdentifierFormat.CAMEL_CASE;
        }

        boolean printlnArgumentCheck = json.optBoolean("mandatory-variable-or-literal-in-println", false);
        boolean readInputArgumentCheck = json.optBoolean("mandatory-variable-or-literal-in-readInput", false);

        return new LintConfig(identifierFormat, printlnArgumentCheck, readInputArgumentCheck);
    }

    /** Replica la lógica de tu loadProgram del CLI */
    private Program loadProgram(InputStream src, PrintScriptVersion version, ErrorHandler handler) {
        try {
            Reader reader = new BufferedReader(new InputStreamReader(src, StandardCharsets.UTF_8));

            var lexerConfig = LexerConfigurations.INSTANCE.getConfiguration(version);
            var tokenResult = new StreamLexer(reader, lexerConfig).tokenize();

            if (tokenResult instanceof Result.Failure<?> failure) {
                reportError(failure.getError(), handler);
                return null;
            }

            var tokens = ((Result.Success<?>) tokenResult).getValue();

            var parserConfig = GrammarConfigurations.INSTANCE.getConfiguration(version);
            var parseResult = new ConfigurableParser(parserConfig).parse((List<Token>) tokens);

            if (parseResult instanceof Result.Failure<?> failure) {
                reportError(failure.getError(), handler);
                return null;
            }

            Program program = (Program) ((Result.Success<?>) parseResult).getValue();

            // Semantic check
            var semanticConfig = SemanticConfigurations.INSTANCE.getConfiguration(version);
            var semanticErrors = new SemanticAnalyzer(semanticConfig).analyze(program);

            if (!semanticErrors.isEmpty()) {
                for (Object err : semanticErrors) {
                    reportError(err, handler);
                }
                return null;
            }

            return program;

        } catch (Exception e) {
            handler.reportError(e.getMessage() != null ? e.getMessage() : e.toString());
            return null;
        }
    }

    private void reportError(Object error, ErrorHandler handler) {
        if (error instanceof CompilerError compilerError) {
            handler.reportError(compilerError.getMessage());
        } else if (error instanceof List<?> list) {
            for (Object e : list) {
                reportError(e, handler);
            }
        } else {
            handler.reportError(String.valueOf(error));
        }
    }
}