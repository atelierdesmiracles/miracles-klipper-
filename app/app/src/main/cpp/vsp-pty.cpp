#include <string.h>
#include <jni.h>
#include <string>
#include <android/log.h>
#include <stdio.h>
#include "real_pty.h"
#include <pthread.h>
#include <termios.h>
#include <fcntl.h>
#include <sys/ioctl.h>
#include <sys/select.h>
#include <sys/types.h>
#include <stddef.h>
#include <sys/wait.h>
#include <unistd.h>
#include <string.h>
#include <stdlib.h>
#include <sys/stat.h>
#include <thread>
#include <errno.h>

#define BUFSIZE 512
int master = -1;
static JavaVM *jvm = nullptr;
JNIEnv *storedEnv;
jmethodID stringCallback;
jweak storeWeakListener;
jclass serialDataClass;
jmethodID serialDataConstructor;
pthread_t ptyThreadHandle = 0;
int ptyWakePipe[2] = {-1, -1};
static std::string serialPipePath;

static jint getBaudrate(speed_t baudrate)
{
    switch (baudrate)
    {
    case B0:
        return 0;
    case B50:
        return 50;
    case B75:
        return 75;
    case B110:
        return 110;
    case B134:
        return 134;
    case B150:
        return 150;
    case B200:
        return 200;
    case B300:
        return 300;
    case B600:
        return 600;
    case B1200:
        return 1200;
    case B1800:
        return 1800;
    case B2400:
        return 2400;
    case B4800:
        return 4800;
    case B9600:
        return 9600;
    case B19200:
        return 19200;
    case B38400:
        return 38400;
    case B57600:
        return 57600;
    case B115200:
        return 115200;
    case B230400:
        return 230400;
    case B460800:
        return 460800;
    case B500000:
        return 500000;
    case B576000:
        return 576000;
    case B921600:
        return 921600;
    case B1000000:
        return 1000000;
    case B1152000:
        return 1152000;
    case B1500000:
        return 1500000;
    case B2000000:
        return 2000000;
    case B2500000:
        return 2500000;
    case B3000000:
        return 3000000;
    case B3500000:
        return 3500000;
    case B4000000:
        return 4000000;
    default:
        return 250000;
    }
}

extern "C"
{

    void passReceivedData(unsigned char *val, size_t dataSize, speed_t baudrate, tcflag_t cIflag, tcflag_t cOflag, tcflag_t cCflag, tcflag_t CLflag)
    {
        __android_log_print(ANDROID_LOG_VERBOSE, "GetEnv:", " start Callback to JNL\n");
        JNIEnv *gEnv;

        if (nullptr == jvm)
        {
            __android_log_print(ANDROID_LOG_ERROR, "GetEnv:", "  No VM  \n");
            return;
        }

        JavaVMAttachArgs args;
        args.version = JNI_VERSION_1_6; // set your JNI version
        args.name = nullptr;
        args.group = nullptr;

        int getEnvStat = jvm->GetEnv(reinterpret_cast<void **>(&gEnv), JNI_VERSION_1_6);

        if (getEnvStat == JNI_EDETACHED)
        {
            __android_log_print(ANDROID_LOG_ERROR, "GetEnv:", " not attached\n");
            if (jvm->AttachCurrentThread(&gEnv, &args) != 0)
            {
                __android_log_print(ANDROID_LOG_ERROR, "GetEnv:", " Failed to attach\n");
                return;
            }
        }
        else if (getEnvStat == JNI_OK)
        {
            __android_log_print(ANDROID_LOG_VERBOSE, "GetEnv:", " JNI_OK\n");
        }
        else if (getEnvStat == JNI_EVERSION)
        {
            __android_log_print(ANDROID_LOG_ERROR, "GetEnv:", " version not supported\n");
        }

        jbyteArray serialDataArr = gEnv->NewByteArray(static_cast<jsize>(dataSize));
        if (serialDataArr == nullptr)
        {
            if (getEnvStat == JNI_EDETACHED)
                jvm->DetachCurrentThread();
            return;
        }
        gEnv->SetByteArrayRegion(serialDataArr, 0, static_cast<jsize>(dataSize), reinterpret_cast<const jbyte *>(val));

        // SerialData constructor: byte array plus baudrate and four termios flags.
        jobject object = gEnv->NewObject(
            serialDataClass,
            serialDataConstructor,
            serialDataArr,
            static_cast<jint>(getBaudrate(baudrate)),
            static_cast<jint>(cIflag),
            static_cast<jint>(cOflag),
            static_cast<jint>(cCflag),
            static_cast<jint>(CLflag)
        );
        gEnv->DeleteLocalRef(serialDataArr);

        jobject listener = gEnv->NewLocalRef(storeWeakListener);
        if (object != nullptr && listener != nullptr && stringCallback != nullptr)
            gEnv->CallVoidMethod(listener, stringCallback, object);
        if (object != nullptr)
            gEnv->DeleteLocalRef(object);
        if (listener != nullptr)
            gEnv->DeleteLocalRef(listener);

        if (gEnv->ExceptionCheck())
        {
            __android_log_print(ANDROID_LOG_ERROR, "GetEnv:", "Exception in USB serial callback");
            gEnv->ExceptionDescribe();
            gEnv->ExceptionClear();
        }
        if (getEnvStat == JNI_EDETACHED)
            jvm->DetachCurrentThread();
    }
}

static void* ptyThread(void* irrelevant)
{
    int slave;
    __android_log_print(ANDROID_LOG_VERBOSE, "TAG", "PtyThread getting ready");

    char name[256];
    if (openpty(&master, &slave, name, nullptr, nullptr) != 0)
    {
        __android_log_print(ANDROID_LOG_ERROR, "TAG", "openpty failed");
        return nullptr;
    }
    __android_log_print(ANDROID_LOG_VERBOSE, "TAG", "PtyThread called openpty");

    if (serialPipePath.empty())
    {
        __android_log_print(ANDROID_LOG_ERROR, "TAG", "Serial pipe path was not provided");
        close(master);
        master = -1;
        close(slave);
        return nullptr;
    }
    unlink(serialPipePath.c_str());
    if (symlink(name, serialPipePath.c_str()) != 0)
    {
        __android_log_print(ANDROID_LOG_ERROR, "TAG", "Could not create serialpipe symlink: %s", strerror(errno));
        close(master);
        master = -1;
        close(slave);
        return nullptr;
    }
    __android_log_print(ANDROID_LOG_INFO, "TAG", "USB serial PTY linked at %s", serialPipePath.c_str());

    // Prepare fds
    fd_set rfds;
    int retval, nread, status = 0, nonzero = 1;
    unsigned char buf[BUFSIZE];
    ioctl(master, TIOCPKT, &nonzero); // Packet mode go brr
    while (true)
    {
        FD_ZERO(&rfds);
        FD_SET(master, &rfds);
        FD_SET(ptyWakePipe[0], &rfds);
        const int maxFd = master > ptyWakePipe[0] ? master : ptyWakePipe[0];
        retval = select(maxFd + 1, &rfds, nullptr, nullptr, nullptr);
        if (retval < 0)
        {
            if (errno == EINTR)
                continue;
            __android_log_print(ANDROID_LOG_ERROR, "TAG", "select failed");
            break;
        }
        if (FD_ISSET(ptyWakePipe[0], &rfds))
            break;
        if (!FD_ISSET(master, &rfds))
            continue;

        if ((nread = read(master, buf, BUFSIZE - 1)) < 0)
        {
            if (errno == EINTR)
                continue;
            __android_log_print(ANDROID_LOG_ERROR, "TAG", "read error: %s", strerror(errno));
            break;
        }
        else if (nread == 0)
        {
            continue;
        }
        else
        {
            if (*buf == TIOCPKT_DATA)
                __android_log_print(ANDROID_LOG_VERBOSE, "TAG", "TIOCPKT_DATA\n");

            if (*buf == TIOCPKT_START)
                __android_log_print(ANDROID_LOG_VERBOSE, "TAG", "TIOCPKT_START\n");

            if (*buf == TIOCPKT_STOP)
                __android_log_print(ANDROID_LOG_VERBOSE, "TAG", "TIOCPKT_STOP\n");

            if (*buf == TIOCPKT_IOCTL)
                __android_log_print(ANDROID_LOG_VERBOSE, "TAG", "IOCTL\n");

            if (*buf == TIOCPKT_FLUSHREAD)
                __android_log_print(ANDROID_LOG_VERBOSE, "TAG", "TIOCPKT_FLUSHREAD\n");

            if (*buf == TIOCPKT_FLUSHWRITE)
                __android_log_print(ANDROID_LOG_VERBOSE, "TAG", "TIOCPKT_FLUSHWRITE\n");

            if (*buf == TIOCPKT_DOSTOP)
                __android_log_print(ANDROID_LOG_VERBOSE, "TAG", "TIOCPKT_DOSTOP\n");

            if (*buf == TIOCPKT_NOSTOP)
                __android_log_print(ANDROID_LOG_VERBOSE, "TAG", "TIOCPKT_NOSTOP\n");

            struct termios tio={};
            tcgetattr(master, &tio);
            __android_log_print(ANDROID_LOG_VERBOSE, "TAG", "Baudrate: %d\n", cfgetospeed(&tio));
            if ((tio.c_cflag & CSIZE) == CS8)
                __android_log_print(ANDROID_LOG_VERBOSE, "TAG", "8 b i t \n");

            if ((tio.c_cflag & CSIZE) == CS7)
                __android_log_print(ANDROID_LOG_VERBOSE, "TAG", "7 b i t \n");

            if ((tio.c_cflag & CSIZE) == CS6)
                __android_log_print(ANDROID_LOG_VERBOSE, "TAG", "6 b i t \n");

            if ((tio.c_cflag & CSIZE) == CS5)
                __android_log_print(ANDROID_LOG_VERBOSE, "TAG", "5 b i t \n");

            if (tio.c_cflag & CSTOPB)
                __android_log_print(ANDROID_LOG_VERBOSE, "TAG", "2 stop bits\n");

            if (tio.c_cflag & PARENB)
                __android_log_print(ANDROID_LOG_VERBOSE, "TAG", "Got parity\n");

            if (tio.c_cflag & PARODD)
                __android_log_print(ANDROID_LOG_VERBOSE, "TAG", "Odd parity\n");

            passReceivedData(buf, nread, cfgetospeed(&tio), tio.c_iflag, tio.c_oflag, tio.c_cflag, tio.c_lflag);
        }
    }

    close(master);
    master = -1;
    close(slave);
    if (!serialPipePath.empty())
        unlink(serialPipePath.c_str());
    return nullptr;
}

extern "C"
{

    JNIEXPORT void JNICALL Java_com_octo4a_serial_VSPPty_writeData(JNIEnv *env, jobject obj, jbyteArray data)
    {
        if (master < 0)
        {
            __android_log_print(ANDROID_LOG_WARN, "TAG", "Ignoring serial write because PTY is not open");
            return;
        }
        jsize numBytes = env->GetArrayLength(data);
        jbyte *lib = env->GetByteArrayElements(data, nullptr);
        if (lib == nullptr)
            return;
        const ssize_t written = write(master, lib, static_cast<size_t>(numBytes));
        if (written < 0)
            __android_log_print(ANDROID_LOG_ERROR, "TAG", "PTY write failed: %s", strerror(errno));
        env->ReleaseByteArrayElements(data, lib, JNI_ABORT);
    }

    JNIEXPORT jint JNICALL Java_com_octo4a_serial_VSPPty_getBaudrate(JNIEnv *env, jobject obj, jint data)
    {
        return getBaudrate(data);
    }

    JNIEXPORT void JNICALL Java_com_octo4a_serial_VSPPty_setVSPListener(JNIEnv *env, jobject instance, jobject listener)
    {
        env->GetJavaVM(&jvm);
        storedEnv = env;

        storeWeakListener = env->NewWeakGlobalRef(listener);
        serialDataClass = (jclass) env->NewGlobalRef(env->FindClass("com/octo4a/serial/SerialData"));
        serialDataConstructor = env->GetMethodID(serialDataClass, "<init>", "([BIIIII)V");
        jclass clazz = env->GetObjectClass(storeWeakListener);
        stringCallback = env->GetMethodID(clazz, "onDataReceived", "(Lcom/octo4a/serial/SerialData;)V");

        __android_log_print(ANDROID_LOG_VERBOSE, "GetEnv:", " Subscribe to Listener  OK \n");
        if (nullptr == stringCallback)
            return;
    }

    JNIEXPORT void JNICALL Java_com_octo4a_serial_VSPPty_runPtyThread(JNIEnv *env, jobject instance, jstring filesDirectory)
    {
        if (filesDirectory == nullptr)
        {
            __android_log_print(ANDROID_LOG_ERROR, "TAG", "App files directory is null");
            return;
        }
        const char *directory = env->GetStringUTFChars(filesDirectory, nullptr);
        if (directory == nullptr)
            return;
        serialPipePath = std::string(directory) + "/serialpipe";
        env->ReleaseStringUTFChars(filesDirectory, directory);

        if (ptyThreadHandle == 0) {
            if (pipe(ptyWakePipe) != 0) {
                __android_log_print(ANDROID_LOG_ERROR, "TAG", "Could not create PTY stop pipe");
                ptyWakePipe[0] = -1;
                ptyWakePipe[1] = -1;
                return;
            }
            if (pthread_create(&ptyThreadHandle, nullptr, &ptyThread, nullptr) != 0) {
                close(ptyWakePipe[0]);
                close(ptyWakePipe[1]);
                ptyWakePipe[0] = -1;
                ptyWakePipe[1] = -1;
                ptyThreadHandle = 0;
                __android_log_print(ANDROID_LOG_ERROR, "TAG", "Could not start PTY thread");
            }
        }
    }

    JNIEXPORT void JNICALL Java_com_octo4a_serial_VSPPty_cancelPtyThread(JNIEnv *env, jobject instance)
    {
        if (ptyThreadHandle != 0) {
            const char stopSignal = 1;
            if (ptyWakePipe[1] >= 0)
                write(ptyWakePipe[1], &stopSignal, 1);
            pthread_join(ptyThreadHandle, nullptr);
            ptyThreadHandle = 0;
            if (ptyWakePipe[0] >= 0)
                close(ptyWakePipe[0]);
            if (ptyWakePipe[1] >= 0)
                close(ptyWakePipe[1]);
            ptyWakePipe[0] = -1;
            ptyWakePipe[1] = -1;
        }
    }

    JNIEXPORT void JNICALL Java_com_octo4a_serial_VSPPty_createEventPipe(JNIEnv *env, jobject instance)
    {
        int res = mkfifo("/data/data/com.octo4a/files/bootstrap/bootstrap/eventPipe", S_IRWXO | S_IRWXU);
        if (res != 0)
        {
            __android_log_print(ANDROID_LOG_VERBOSE, "MkFifo:", " Can't create event pipe\n");
        }
    }
}
