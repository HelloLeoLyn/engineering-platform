package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.ImplementationTask;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * TaskGraphValidator（EP-WORK-007/008 指令 §七）。
 *
 * ImplementationTasks 必须支持 dependency DAG。
 * 检测：unknown dependency（引用了不存在的 taskId）+ cycle。
 * 纯计算，无副作用。
 */
public final class TaskGraphValidator {

    private TaskGraphValidator() {
    }

    public static ImplementationTask.DagResult validate(List<ImplementationTask> tasks) {
        Set<String> ids = new HashSet<>();
        for (ImplementationTask t : tasks) {
            ids.add(t.taskId());
        }

        List<String> unknown = new ArrayList<>();
        for (ImplementationTask t : tasks) {
            for (String dep : t.dependencies()) {
                if (!ids.contains(dep)) {
                    unknown.add(t.taskId() + " -> " + dep);
                }
            }
        }

        List<String> cycles = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> onStack = new HashSet<>();
        for (ImplementationTask t : tasks) {
            if (!visited.contains(t.taskId())) {
                dfs(t, tasks, visited, onStack, new ArrayList<>(), cycles);
            }
        }

        return new ImplementationTask.DagResult(unknown.isEmpty() && cycles.isEmpty(), unknown, cycles);
    }

    private static void dfs(ImplementationTask task, List<ImplementationTask> tasks,
                            Set<String> visited, Set<String> onStack,
                            List<String> stack, List<String> cycles) {
        visited.add(task.taskId());
        onStack.add(task.taskId());
        stack.add(task.taskId());

        for (String dep : task.dependencies()) {
            if (onStack.contains(dep)) {
                // cycle found: stack from dep to current
                int start = stack.indexOf(dep);
                List<String> cycle = new ArrayList<>(stack.subList(start, stack.size()));
                cycle.add(dep);
                cycles.add(String.join(" -> ", cycle));
                continue;
            }
            if (!visited.contains(dep)) {
                ImplementationTask next = findTask(tasks, dep);
                if (next != null) {
                    dfs(next, tasks, visited, onStack, stack, cycles);
                }
            }
        }
        stack.remove(stack.size() - 1);
        onStack.remove(task.taskId());
    }

    private static ImplementationTask findTask(List<ImplementationTask> tasks, String taskId) {
        for (ImplementationTask t : tasks) {
            if (t.taskId().equals(taskId)) {
                return t;
            }
        }
        return null;
    }
}
