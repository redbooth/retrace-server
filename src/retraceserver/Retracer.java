/*
 * Copyright (c) Air Computing Inc., 2013.
 * Author: Gregory Schlomoff <greg@aerofs.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package retraceserver;

import com.sun.istack.internal.Nullable;
import proguard.obfuscate.MappingProcessor;
import proguard.obfuscate.MappingReader;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class Retracer implements MappingProcessor
{
    // Maps an obfuscated class name to the unobfuscated name
    private final Map<String, String> _classMap = new HashMap<String, String>();

    // map(obfuscated_class_name -> map(obfuscated_method_name -> set(methods)))
    private final Map<String, Map<String, Set<MethodInfo>>> _classMethodMap = new HashMap<String, Map<String, Set<MethodInfo>>>();

    public Retracer(File mappingFile) throws IOException
    {
        // Parse the mapping file and call our methods from the MappingProcessor interface
        // This is the only place where the data structures in the Retracer class are written
        // All other method in this class only read to those structures

        new MappingReader(mappingFile).pump(this);
    }

    public static class Result
    {
        public String className;
        public List<String> methodNames = new ArrayList<String>();
    }

    /**
     * This method is thread-safe
     * If the className or the methodName can't be unobfuscated, the obfuscated versions will be returned
     */
    public Result retrace(String className, @Nullable String methodName, int lineNumber)
    {
        Result result = new Result();
        result.className = originalClassName(className);
        if (methodName != null) {
            result.methodNames = originalMethodNames(result.className, methodName, lineNumber);
        }
        return result;
    }

    private List<String> originalMethodNames(String className, String obfuscatedMethodName, int lineNumber)
    {
        List<String> result = new ArrayList<String>();

        // Class name -> obfuscated method names.
        Map<String, Set<MethodInfo>> methodMap = _classMethodMap.get(className);
        if (methodMap != null) {
            // Obfuscated method names -> methods.
            Set<MethodInfo> methodSet = methodMap.get(obfuscatedMethodName);
            if (methodSet != null) {
                for (MethodInfo methodInfo : methodSet) {
                    if (methodInfo.matches(lineNumber)) {
                        result.add(methodInfo.originalName);
                    }
                }
            }
        }

        if (result.isEmpty()) result.add(obfuscatedMethodName);
        return result;
    }

    /**
     * Returns the original class name.
     */
    private String originalClassName(String obfuscatedClassName)
    {
        String originalClassName = _classMap.get(obfuscatedClassName);
        return (originalClassName != null) ? originalClassName : obfuscatedClassName;
    }

    @Override
    public boolean processClassMapping(String className, String newClassName)
    {
        // Obfuscated class name -> original class name.
        _classMap.put(newClassName, className);
        return true;
    }

    @Override
    public void processFieldMapping(String className, String fieldType, String fieldName, String newFieldName)
    {
    }

    @Override
    public void processMethodMapping(String className, int firstLineNumber, int lastLineNumber, String methodReturnType,
                                     String methodName, String methodArguments, String newMethodName)
    {
        // Original class name -> obfuscated method names.
        Map<String, Set<MethodInfo>> methodMap = _classMethodMap.get(className);
        if (methodMap == null) {
            methodMap = new HashMap<String, Set<MethodInfo>>();
            _classMethodMap.put(className, methodMap);
        }

        // Obfuscated method name -> methods.
        Set<MethodInfo> methodSet = methodMap.get(newMethodName);
        if (methodSet == null) {
            methodSet = new LinkedHashSet<MethodInfo>();
            methodMap.put(newMethodName, methodSet);
        }

        // Add the method information.
        methodSet.add(new MethodInfo(firstLineNumber, lastLineNumber, methodName));
    }

    private static class MethodInfo
    {
        private int firstLineNumber;
        private int lastLineNumber;
        private String originalName;

        private MethodInfo(int firstLineNumber, int lastLineNumber, String originalName)
        {
            this.firstLineNumber = firstLineNumber;
            this.lastLineNumber  = lastLineNumber;
            this.originalName    = originalName;
        }

        private boolean matches(int lineNumber)
        {
            return (firstLineNumber <= lineNumber && lineNumber <= lastLineNumber) || (lastLineNumber == 0);
        }
    }
}
