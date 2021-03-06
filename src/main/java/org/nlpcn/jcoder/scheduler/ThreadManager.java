package org.nlpcn.jcoder.scheduler;

import org.nlpcn.jcoder.constant.Constants;
import org.nlpcn.jcoder.domain.KeyValue;
import org.nlpcn.jcoder.domain.Task;
import org.nlpcn.jcoder.domain.TaskInfo;
import org.nlpcn.jcoder.job.MasterRunTaskJob;
import org.nlpcn.jcoder.run.java.JavaRunner;
import org.nlpcn.jcoder.service.TaskService;
import org.nlpcn.jcoder.util.DateUtils;
import org.nlpcn.jcoder.util.StaticValue;
import org.nlpcn.jcoder.util.StringUtil;
import org.quartz.SchedulerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

public class ThreadManager {

	private static final Logger LOG = LoggerFactory.getLogger(QuartzSchedulerManager.class);

	private static final AtomicLong JOB_ID = new AtomicLong();

	/**
	 * 增加一个task 只有master可以添加任务
	 */
	public synchronized static boolean addJob(String groupName, String taskName, String scheduleStr) throws TaskException, SchedulerException {
		if (StringUtil.isBlank(scheduleStr)) {
			MasterRunTaskJob.addQueue(KeyValue.with(groupName, taskName));
			return true;
		}

		boolean flag;
		try {
			flag = QuartzSchedulerManager.addJob(groupName + Constants.GROUP_TASK_SPLIT + taskName, scheduleStr);
		} catch (Exception e) {
			flag = false;
			e.printStackTrace();
			LOG.error(e.getMessage(), e);
		}
		return flag;
	}

	/**
	 * 更新映射
	 */
	public static void addApi(Task oldTask, Task newTask) {

		oldTask.codeInfo().getExecuteMethods().forEach(m -> {
			StaticValue.space().removeMapping(oldTask.getGroupName(), oldTask.getName(), m.getName());
		});


		try {
			new JavaRunner(newTask).compile();
			/**
			 * 注册api到共享空间
			 */
			newTask.codeInfo().getExecuteMethods().forEach(m -> {
				StaticValue.space().addMapping(newTask.getGroupName(), newTask.getName(), m.getName());
			});

		} catch (Exception e) {
			LOG.error("compile {}/{} err ", newTask.getGroupName(), newTask.getName(), e);
		}
	}


	/**
	 * 停用一个task并从定时任务中删除
	 */
	public static void removeTaskJob(String GroupName, String taskName) throws SchedulerException {
		QuartzSchedulerManager.stopTaskJob(GroupName + Constants.GROUP_TASK_SPLIT + taskName);
	}

	/**
	 * 运行一个task
	 */
	public static void run(Task task) throws TaskException {
		//如果是while或者一次性任务将不再添加进来
		if (StringUtil.isBlank(task.getScheduleStr()) || "while".equals(task.getScheduleStr().toLowerCase())) {
			if (TaskRunManager.checkTaskExists(task.getName())) {
				LOG.warn("task " + task.getName() + " has been in joblist so skip it ");
				return;
			}
		}
		String threadName = task.getName() + "@" + JOB_ID.getAndIncrement() + "@" + DateUtils.formatDate(new Date(), "yyyyMMddHHmmss");

		TaskRunManager.runTaskJob(new TaskJob(threadName, task));
	}

	/**
	 * 运行一个taskJob
	 */
	public static void run(TaskJob taskJob) throws TaskException {
		TaskRunManager.runTaskJob(taskJob);
	}

	/**
	 * 停止一个task
	 */
	private static synchronized void stopTask(String groupName, String taskName) throws TaskException {
		try {
			// 从任务中移除
			try {
				TaskRunManager.stopAll(groupName, taskName);
			} catch (Exception e) {
				LOG.error(e.getMessage(), e);
				e.printStackTrace();
			}
			// 进行二次停止

			TaskRunManager.stopAll(groupName, taskName);


		} catch (Exception e) {
			LOG.error(e.getMessage(), e);
			throw new TaskException(e.getMessage());
		}
	}

	/**
	 * 刷新task 相当于从定时任务中移除，并且重新插入,非线程安全.如果调用记得在外层锁定对象
	 */
	public static void flush(Task oldTask, Task newTask) throws Exception {
		if (oldTask != null && StringUtil.isNotBlank(oldTask.getName())) {

			if (oldTask.getType() == 2) {
				LOG.info("to stop oldTask " + oldTask.getName() + " BEGIN! ");
				stopTask(oldTask.getGroupName(), oldTask.getName());
				LOG.info("to stop oldTask " + oldTask.getName() + " OK! ");
			} else if (oldTask.getType() == 1) {
				LOG.info("to remove Api oldTask " + oldTask.getName() + " BEGIN! ");
				stopActionAndRemove(oldTask.getGroupName(), oldTask.getName());
				LOG.info("to remove Api stop oldTask " + oldTask.getName() + " BEGIN! ");
			}

		}

		Thread.sleep(1000L);

		if (newTask == null || StringUtil.isBlank(newTask.getName()) || newTask.getStatus() == 0) {
			return;
		}

		if (newTask.getType() == 1) {
			addApi(oldTask, newTask);
		}

	}

	private static void stopActionAndRemove(String groupName, String taskName) throws TaskException {
		StaticValue.MAPPING.remove(groupName, taskName); // remove url from api mapping
		try {
			// 从任务中移除
			try {
				ActionRunManager.stopAll(groupName, taskName);
			} catch (Exception e) {
				LOG.error(e.getMessage(), e);
				e.printStackTrace();
			}
			// 进行二次停止

			ActionRunManager.stopAll(groupName, taskName);

		} catch (Exception e) {
			LOG.error(e.getMessage(), e);
			throw new TaskException(e.getMessage());
		}

	}

	/**
	 * 判断一个定时任务是否存在
	 */
	public static boolean checkExists(String groupName, String taskName) {
		try {
			String key = groupName + Constants.GROUP_TASK_SPLIT + taskName;
			return QuartzSchedulerManager.checkExists(key) || TaskRunManager.checkExists(key);
		} catch (Exception e) {
			e.printStackTrace();
			LOG.error(e.getMessage());
		}
		return true;
	}

	/**
	 * 取得所有的在运行状态的线程
	 */
	public static List<TaskInfo> getAllThread() throws TaskException {
		Collection<Entry<String, TaskJob>> entrys = TaskRunManager.getTaskList();
		List<TaskInfo> threads = new ArrayList<>();
		for (Entry<String, TaskJob> entry : entrys) {
			Task task = entry.getValue().getTask();
			if (entry.getValue().isInterrupted()) {
				task.setRunStatus("Stoping");
			} else if (entry.getValue().isAlive()) {
				task.setRunStatus("Runging");
			} else if (entry.getValue().isOver()) {
				task.setRunStatus("Stoped");
			} else {
				task.setRunStatus("UnKnow");
			}
			threads.add(new TaskInfo(entry.getKey(), task, entry.getValue().getStartTime()));
		}
		return threads;
	}

	/**
	 * 获得所有的调度任务
	 */
	public static List<TaskInfo> getAllScheduler() throws SchedulerException {
		List<String> taskList = QuartzSchedulerManager.getTaskList();
		List<TaskInfo> schedulers = new ArrayList<>();
		for (String jobKey : taskList) {
			String[] split = jobKey.split(Constants.GROUP_TASK_SPLIT);
			Task taskByCache = TaskService.findTaskByCache(split[0], split[1]);
			if (taskByCache == null) {
				LOG.warn("not found task in cache {}/{}", split[0], split[1]);
			} else {
				TaskInfo taskInfo = new TaskInfo(taskByCache.getScheduleStr(), split[1], split[0]);
				taskInfo.setDescription(taskByCache.getDescription());
				schedulers.add(taskInfo);
			}

		}
		return schedulers;
	}

	public static List<TaskInfo> getAllAction() {
		Set<String> actionList = ActionRunManager.getActionList();
		List<TaskInfo> actions = new ArrayList<>();
		for (String key : actionList) {
			TaskInfo taskInfo = null;
			try {
				String[] split = key.split("@");
				Task task = TaskService.findTaskByCache(split[0], split[1]);
				if (task == null) {
					task = new Task();
				}
				task.setRunStatus("Runging");
				taskInfo = new TaskInfo(key, task, DateUtils.getDate(split[4], "yyyyMMddHHmmss").getTime());
			} catch (Exception e) {
				taskInfo = new TaskInfo();
				LOG.error(e.getMessage(), e);
			}
			taskInfo.setName(key);
			actions.add(taskInfo);
		}
		return actions;
	}

	/**
	 * 停止调度任务
	 */
	public static void stopScheduler() {
		try {
			QuartzSchedulerManager.stopScheduler();
		} catch (SchedulerException e) {
			e.printStackTrace();
			LOG.error(e.getMessage(), e);
		}
	}

	/**
	 * 开启调度任务
	 */
	public static void startScheduler() {
		try {
			QuartzSchedulerManager.startScheduler();
		} catch (SchedulerException e) {
			e.printStackTrace();
			LOG.error(e.getMessage(), e);
		}
	}

	/**
	 * 增加taskaction到运行队列中
	 */
	public static void add2ActionTask(String key, Thread thread) {
		ActionRunManager.add2ThreadPool(key, thread);
	}

	/**
	 * 强行停止一个 thread
	 */
	public static boolean stop(String key) throws TaskException {
		if (ActionRunManager.checkExists(key)) {
			return ActionRunManager.stop(key);
		}

		if (TaskRunManager.checkExists(key)) {
			return TaskRunManager.stop(key);
		}

		return true;

	}

	/**
	 * @param key
	 */
	public static void removeTaskIfOver(String key) {
		TaskRunManager.removeIfOver(key);
	}

	public static void removeActionIfOver(String key) {
		ActionRunManager.removeIfOver(key);
	}

	/**
	 * 判断一个任务是否存在
	 */
	public static boolean checkActionExists(String taskName) {
		return ActionRunManager.checkExists(taskName);
	}

	public static void stopAllJob(String groupName, String taskName) {
		TaskRunManager.stopAll(groupName, taskName);
	}
}
