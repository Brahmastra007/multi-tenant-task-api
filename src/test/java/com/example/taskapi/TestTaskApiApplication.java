package com.example.taskapi;

import org.springframework.boot.SpringApplication;

public class TestTaskApiApplication {

	public static void main(String[] args) {
		SpringApplication.from(TaskApiApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
