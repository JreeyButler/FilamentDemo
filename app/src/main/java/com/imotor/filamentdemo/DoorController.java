package com.imotor.filamentdemo;

/**
 * @author Yan.Liangliang
 * @date 2025/6/12 下午2:53
 */
public interface DoorController {
    int FRONT_LEFT_DOOR = 1;
    int FRONT_RIGHT_DOOR = 2;

    /**
     * 开启车门
     *
     * @param doorIndex 车门序号
     */
    void openDoor(int doorIndex);

    /**
     * 关闭车门
     *
     * @param doorIndex 车门序号
     */
    void closeDoor(int doorIndex);
}
