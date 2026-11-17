#ifndef _JAVASOFT_JNI_MD_H_
#define _JAVASOFT_JNI_MD_H_

#ifndef JNIEXPORT
  #define JNIEXPORT __declspec(dllexport)
#endif
#ifndef JNIIMPORT
  #define JNIIMPORT __declspec(dllimport)
#endif
#ifndef JNICALL
  #define JNICALL
#endif

typedef int jint;
typedef long long jlong;
typedef signed char jbyte;

#endif /* !_JAVASOFT_JNI_MD_H_ */
