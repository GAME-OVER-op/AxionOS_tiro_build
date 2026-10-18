/* SPDX-License-Identifier: Apache-2.0 */
/* Nubia trigger merge. No task polling, no synthetic taps on unassigned points. */
#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/epoll.h>
#include <sys/inotify.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <unistd.h>
#include <android/log.h>

#define TAG "NubiaTriggers"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR,TAG,__VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,TAG,__VA_ARGS__)
#define DIR_PATH "/data/system/nubia_triggers"
#define CONFIG_PATH DIR_PATH "/runtime.conf"
#define PROC_L "/proc/nubia_key/sar0/mode_operation"
#define PROC_R "/proc/nubia_key/sar1/mode_operation"
#define MAX_SLOTS 64
#define MAX_EVENTS 512
#define MAX_LINE 2048

struct binding { bool enabled, valid; float x,y; };
struct profile { char pkg[256]; int rotation; struct binding l,r; };
struct config {
    bool enabled, screen, editing;
    int rotation;
    char foreground[256];
    struct profile profiles[192];
    int profiles_n;
};
struct device { int fd; char path[100]; };
struct controller {
    int ep, notify_fd, notify_wd, ufd;
    struct device touch, left, right;
    int slot_max, x_min,x_max,y_min,y_max,major;
    int virt_slots[2];
    int tid[MAX_SLOTS], current_slot, frame_open_slot;
    int finger_count;
    bool virt_down[2], sar_state[2], sar_key[2], active, pending;
    struct input_event frame[MAX_EVENTS];
    size_t frame_n;
    struct binding selected[2];
    int rotation;
};
static struct controller g = {
    .ep=-1,.notify_fd=-1,.notify_wd=-1,.ufd=-1,
    .touch={.fd=-1},.left={.fd=-1},.right={.fd=-1},
};
static bool set_mode(const char *path, bool active) {
    int fd=open(path,O_WRONLY|O_CLOEXEC);
    if(fd<0) { LOGE("open %s: %s",path,strerror(errno)); return false; }
    const char *s=active?"1\n":"2\n";
    ssize_t n=write(fd,s,2); close(fd); return n==2;
}
static void mode_off(void) {set_mode(PROC_L,false);set_mode(PROC_R,false);}
static int watch_add(int fd) {
    if(fd<0) return -1;
    struct epoll_event ev={.events=EPOLLIN|EPOLLERR|EPOLLHUP,.data.fd=fd};
    return epoll_ctl(g.ep,EPOLL_CTL_ADD,fd,&ev);
}
static void watch_del(int fd) { if(fd>=0) epoll_ctl(g.ep,EPOLL_CTL_DEL,fd,NULL); }
static void close_device(struct device *d) {
    if(d->fd>=0) {watch_del(d->fd);close(d->fd);d->fd=-1;}
}
static bool bit(const unsigned long *bits, size_t words, unsigned int id) {
    unsigned int word=id/(8*sizeof(unsigned long));
    return word<words && (bits[word]&(1UL<<(id%(8*sizeof(unsigned long)))))!=0;
}
#define BITS(n) (((n)+8*sizeof(unsigned long)-1)/(8*sizeof(unsigned long)))
static bool has_ev(int fd, unsigned int type, unsigned int code, unsigned int maxbits) {
    unsigned long bits[BITS(KEY_MAX+1)]={0};
    if(maxbits>KEY_MAX+1) return false;
    size_t bytes=BITS(maxbits)*sizeof(unsigned long);
    if(ioctl(fd,EVIOCGBIT(type,bytes),bits)<0) return false;
    return bit(bits,BITS(maxbits),code);
}
static bool is_touch(int fd, const char *name) {
    unsigned long prop[BITS(INPUT_PROP_MAX+1)]={0};
    ioctl(fd,EVIOCGPROP(sizeof(prop)),prop);
    return strncmp(name,"nubia_merged_touch",18)!=0 &&
        bit(prop,BITS(INPUT_PROP_MAX+1),INPUT_PROP_DIRECT) &&
        has_ev(fd,EV_ABS,ABS_MT_SLOT,ABS_MAX+1) &&
        has_ev(fd,EV_ABS,ABS_MT_TRACKING_ID,ABS_MAX+1) &&
        has_ev(fd,EV_KEY,BTN_TOUCH,KEY_MAX+1);
}
static int touch_score(const char *name) {
    int score=0;
    if(strcasestr(name,"goodix"))score+=6;
    if(strcasestr(name,"touchscreen"))score+=4;
    if(strcasestr(name,"touch"))score+=2;
    if(strcasestr(name,"ts"))score++;
    return score;
}
static bool discover(void) {
    int best_touch=-1;
    DIR *dir=opendir("/dev/input"); if(!dir){LOGE("input scan: %s",strerror(errno));return false;}
    struct dirent *ent;
    while((ent=readdir(dir))) {
        if(strncmp(ent->d_name,"event",5)!=0) continue;
        char path[100]; if(snprintf(path,sizeof(path),"/dev/input/%s",ent->d_name)>=(int)sizeof(path))continue;
        int fd=open(path,O_RDONLY|O_CLOEXEC|O_NONBLOCK); if(fd<0)continue;
        char name[256]={0}; ioctl(fd,EVIOCGNAME(sizeof(name)),name);
        if(is_touch(fd,name)) {
            int score=touch_score(name);
            if(score>best_touch) {
                close_device(&g.touch);
                g.touch.fd=fd;strcpy(g.touch.path,path);best_touch=score;
                continue;
            }
        }
        bool distance=has_ev(fd,EV_ABS,ABS_DISTANCE,ABS_MAX+1);
        bool left=distance && has_ev(fd,EV_KEY,KEY_F7,KEY_MAX+1);
        bool right=distance && has_ev(fd,EV_KEY,KEY_F8,KEY_MAX+1);
        if(left && g.left.fd<0) {g.left.fd=fd;strcpy(g.left.path,path);continue;}
        if(right && g.right.fd<0) {g.right.fd=fd;strcpy(g.right.path,path);continue;}
        close(fd);
    }
    closedir(dir);
    if(g.touch.fd<0 || g.left.fd<0 || g.right.fd<0) {
        LOGE("missing inputs: touch=%d left=%d right=%d",g.touch.fd,g.left.fd,g.right.fd);
        close_device(&g.touch);close_device(&g.left);close_device(&g.right);
        return false;
    }
    return true;
}
static int get_abs(int code, int *min, int *max) {
    struct input_absinfo info={0};
    if(ioctl(g.touch.fd,EVIOCGABS(code),&info)<0)return -1;
    *min=info.minimum;*max=info.maximum;return 0;
}
static bool emit(uint16_t type,uint16_t code,int value) {
    struct input_event ev={.type=type,.code=code,.value=value};
    return g.ufd>=0 && write(g.ufd,&ev,sizeof(ev))==(ssize_t)sizeof(ev);
}
static void sync_frame(void) {emit(EV_SYN,SYN_REPORT,0);}
static int count_active(void) {
    int n=0;for(int i=0;i<=g.slot_max;i++) if(g.tid[i]>=0)n++;
    return n+(int)g.virt_down[0]+(int)g.virt_down[1];
}
static void tool_buttons(void) {
    int n=count_active();
    emit(EV_KEY,BTN_TOUCH,n>0);
    emit(EV_KEY,BTN_TOOL_FINGER,n==1);
    emit(EV_KEY,BTN_TOOL_DOUBLETAP,n==2);
    emit(EV_KEY,BTN_TOOL_TRIPLETAP,n==3);
    emit(EV_KEY,BTN_TOOL_QUADTAP,n==4);
    emit(EV_KEY,BTN_TOOL_QUINTTAP,n>=5);
}
static void release_virtual(int i) {
    if(!g.virt_down[i])return;
    emit(EV_ABS,ABS_MT_SLOT,g.virt_slots[i]);
    emit(EV_ABS,ABS_MT_TRACKING_ID,-1);
    g.virt_down[i]=false;
    tool_buttons();sync_frame();
}
static int map_coordinate(float normalized,int min,int max) {
    float v=normalized;
    if (v < 0) v = 0;
    if (v > 1) v = 1;
    return min+(int)((max-min)*v+0.5f);
}
static void down_virtual(int i) {
    if(g.virt_down[i]||!g.selected[i].valid||!g.selected[i].enabled)return;
    /* Normalized coordinates represent the current display orientation. */
    float u=g.selected[i].x, v=g.selected[i].y;
    float x=u,y=v;
    switch(g.rotation) {
    case 1: x=1-v;y=u;break;
    case 2: x=1-u;y=1-v;break;
    case 3: x=v;y=1-u;break;
    default:break;
    }
    emit(EV_ABS,ABS_MT_SLOT,g.virt_slots[i]);
    emit(EV_ABS,ABS_MT_TRACKING_ID,60000+i);
    emit(EV_ABS,ABS_MT_POSITION_X,map_coordinate(x,g.x_min,g.x_max));
    emit(EV_ABS,ABS_MT_POSITION_Y,map_coordinate(y,g.y_min,g.y_max));
    emit(EV_ABS,ABS_MT_TOUCH_MAJOR,g.major>0?g.major/8:1);
    g.virt_down[i]=true;
    tool_buttons();sync_frame();
}
static void reset_all_contacts(void) {
    if(g.ufd<0)return;
    for(int i=0;i<=g.slot_max;i++){
        emit(EV_ABS,ABS_MT_SLOT,i);emit(EV_ABS,ABS_MT_TRACKING_ID,-1);
    }
    for(int i=0;i<2;i++){
        emit(EV_ABS,ABS_MT_SLOT,g.virt_slots[i]);
        emit(EV_ABS,ABS_MT_TRACKING_ID,-1);g.virt_down[i]=false;
    }
    for(int i=0;i<MAX_SLOTS;i++)g.tid[i]=-1;
    tool_buttons();sync_frame();
}
static void stop_merge(void) {
    if(g.ufd>=0)reset_all_contacts();
    if(g.touch.fd>=0) ioctl(g.touch.fd,EVIOCGRAB,0);
    close_device(&g.touch);close_device(&g.left);close_device(&g.right);
    if(g.ufd>=0) {ioctl(g.ufd,UI_DEV_DESTROY);close(g.ufd);g.ufd=-1;}
    g.active=false;g.pending=false;g.frame_n=0;mode_off();
}
static bool create_uinput(void) {
    int mn,mx;
    if(get_abs(ABS_MT_SLOT,&mn,&mx)<0||mx<0||mx>MAX_SLOTS-3)return false;
    g.slot_max=mx;g.virt_slots[0]=mx+1;g.virt_slots[1]=mx+2;
    if(get_abs(ABS_MT_POSITION_X,&g.x_min,&g.x_max)<0 ||
       get_abs(ABS_MT_POSITION_Y,&g.y_min,&g.y_max)<0)return false;
    int major_min=0,major_max=0;
    if(get_abs(ABS_MT_TOUCH_MAJOR,&major_min,&major_max)<0)major_max=64;
    g.major=major_max;
    g.ufd=open("/dev/uinput",O_WRONLY|O_NONBLOCK|O_CLOEXEC);
    if(g.ufd<0){LOGE("uinput open: %s",strerror(errno));return false;}
    if(ioctl(g.ufd,UI_SET_EVBIT,EV_SYN)<0||ioctl(g.ufd,UI_SET_EVBIT,EV_KEY)<0||
       ioctl(g.ufd,UI_SET_EVBIT,EV_ABS)<0||ioctl(g.ufd,UI_SET_PROPBIT,INPUT_PROP_DIRECT)<0)return false;
    int keys[]={BTN_TOUCH,BTN_TOOL_FINGER,BTN_TOOL_DOUBLETAP,BTN_TOOL_TRIPLETAP,
                BTN_TOOL_QUADTAP,BTN_TOOL_QUINTTAP};
    for(size_t i=0;i<sizeof(keys)/sizeof(keys[0]);i++)if(ioctl(g.ufd,UI_SET_KEYBIT,keys[i])<0)return false;
    struct uinput_user_dev ui={0};
    snprintf(ui.name,sizeof(ui.name),"nubia_merged_touch");
    ui.id.bustype=BUS_VIRTUAL;ui.id.vendor=0x19d2;ui.id.product=0x769;
    /* Declare every physical ABS capability, not only X/Y, for faithful forwarding. */
    for(int axis=0;axis<=ABS_MAX;axis++) {
        if(!has_ev(g.touch.fd,EV_ABS,(unsigned int)axis,ABS_MAX+1))continue;
        struct input_absinfo ai;
        if(ioctl(g.touch.fd,EVIOCGABS(axis),&ai)<0)continue;
        if(ioctl(g.ufd,UI_SET_ABSBIT,axis)<0)return false;
        ui.absmin[axis]=ai.minimum;ui.absmax[axis]=ai.maximum;
        ui.absfuzz[axis]=ai.fuzz;ui.absflat[axis]=ai.flat;
    }
    ui.absmin[ABS_MT_SLOT]=0;ui.absmax[ABS_MT_SLOT]=g.virt_slots[1];
    ui.absmin[ABS_MT_TRACKING_ID]=0;ui.absmax[ABS_MT_TRACKING_ID]=65535;
    if(write(g.ufd,&ui,sizeof(ui))!=(ssize_t)sizeof(ui)||ioctl(g.ufd,UI_DEV_CREATE)<0) {
        LOGE("uinput create failed: %s",strerror(errno));return false;
    }
    return true;
}
static bool touch_is_down(void) {
    unsigned long bits[BITS(KEY_MAX+1)]={0};
    return ioctl(g.touch.fd,EVIOCGKEY(sizeof(bits)),bits)<0 ||
           bit(bits,BITS(KEY_MAX+1),BTN_TOUCH);
}
static bool start_merge(void) {
    if(!discover())return false;
    /* While any real finger is already down, retain native input and wait for
       its release via epoll rather than grabbing mid-gesture. */
    if(touch_is_down()) {
        if(watch_add(g.touch.fd)<0){stop_merge();return false;}
        g.pending=true;
        LOGI("waiting for native fingers to lift before touch merge");
        return true;
    }
    if(!create_uinput()){stop_merge();return false;}
    /* NEVER grab physical touch until virtual device is completely ready. */
    if(ioctl(g.touch.fd,EVIOCGRAB,1)<0){LOGE("grab: %s",strerror(errno));stop_merge();return false;}
    for(int i=0;i<MAX_SLOTS;i++)g.tid[i]=-1;
    g.current_slot=0;g.frame_open_slot=0;g.frame_n=0;
    if(watch_add(g.touch.fd)<0 || watch_add(g.left.fd)<0 || watch_add(g.right.fd)<0) {
        LOGE("epoll inputs failed");stop_merge();return false;
    }
    set_mode(PROC_L,g.selected[0].enabled&&g.selected[0].valid);
    set_mode(PROC_R,g.selected[1].enabled&&g.selected[1].valid);
    g.active=true;LOGI("merge enabled; real slots=%d",g.slot_max+1);return true;
}
static bool same_binding(struct binding a,struct binding b) {
    return a.enabled==b.enabled&&a.valid==b.valid&&a.x==b.x&&a.y==b.y;
}
static bool good_package(const char *p) {
    if(!*p||strlen(p)>220)return false;
    for(const unsigned char *s=(const unsigned char *)p;*s;s++)
        if(!((*s>='a'&&*s<='z')||(*s>='A'&&*s<='Z')||(*s>='0'&&*s<='9')||*s=='.'||*s=='_'))return false;
    return true;
}
static void trim(char *s) {size_t n=strlen(s);while(n&&(s[n-1]=='\n'||s[n-1]=='\r'))s[--n]=0;}
static bool read_config(struct config *cfg) {
    memset(cfg,0,sizeof(*cfg));
    FILE *f=fopen(CONFIG_PATH,"r"); if(!f)return false;
    char line[MAX_LINE];bool version=false;
    while(fgets(line,sizeof(line),f)) {
        trim(line);
        if(!strcmp(line,"version=1")) {version=true;continue;}
        if(!strncmp(line,"enabled=",8)){cfg->enabled=atoi(line+8)==1;continue;}
        if(!strncmp(line,"screen=",7)){cfg->screen=atoi(line+7)==1;continue;}
        if(!strncmp(line,"editing=",8)){cfg->editing=atoi(line+8)==1;continue;}
        if(!strncmp(line,"rotation=",9)){cfg->rotation=atoi(line+9);continue;}
        if(!strncmp(line,"foreground=",11)) {
            snprintf(cfg->foreground,sizeof(cfg->foreground),"%s",line+11);continue;
        }
        if(strncmp(line,"profile=",8)||cfg->profiles_n>=192)continue;
        struct profile p={0};int le,lv,re,rv;
        if(sscanf(line+8,"%255[^|]|%d|%d|%f|%f|%d|%d|%f|%f|%d",
             p.pkg,&le,&lv,&p.l.x,&p.l.y,&re,&rv,&p.r.x,&p.r.y,&p.rotation)!=10)continue;
        if(!good_package(p.pkg)||p.rotation<0||p.rotation>3)continue;
        p.l.enabled=le==1;p.l.valid=lv==1&&p.l.x>=0&&p.l.x<=1&&p.l.y>=0&&p.l.y<=1;
        p.r.enabled=re==1;p.r.valid=rv==1&&p.r.x>=0&&p.r.x<=1&&p.r.y>=0&&p.r.y<=1;
        cfg->profiles[cfg->profiles_n++]=p;
    }
    fclose(f);return version;
}
static void refresh(void) {
    struct config c;
    if(!read_config(&c)){LOGE("invalid/unreadable config; disable merge");stop_merge();return;}
    struct binding selected[2]={{0},{0}};
    int rot=c.rotation;
    if(c.enabled&&c.screen&&!c.editing&&good_package(c.foreground)) {
        for(int i=0;i<c.profiles_n;i++){
            struct profile *p=&c.profiles[i];
            if(!strcmp(p->pkg,c.foreground)&&p->rotation==rot){selected[0]=p->l;selected[1]=p->r;break;}
        }
    }
    bool required=(selected[0].enabled&&selected[0].valid)||(selected[1].enabled&&selected[1].valid);
    if((g.active||g.pending) && (!required||!same_binding(g.selected[0],selected[0])||
        !same_binding(g.selected[1],selected[1])||g.rotation!=rot))stop_merge();
    g.selected[0]=selected[0];g.selected[1]=selected[1];g.rotation=rot;
    if(required&&!g.active&&!g.pending)start_merge();
}
static void handle_touch(void) {
    struct input_event ev[64];ssize_t n=read(g.touch.fd,ev,sizeof(ev));
    if(n<=0||n%(ssize_t)sizeof(struct input_event)) {LOGE("touch read failed");stop_merge();return;}
    size_t events=(size_t)n/sizeof(ev[0]);
    for(size_t i=0;i<events;i++) {
        struct input_event e=ev[i];
        if(e.type==EV_SYN && e.code==SYN_DROPPED){LOGE("touch SYN_DROPPED: fail-open");stop_merge();return;}
        if(e.type==EV_ABS && e.code==ABS_MT_SLOT) {
            if(e.value<0||e.value>g.slot_max){stop_merge();return;}
            g.current_slot=e.value;
        }
        if(e.type==EV_ABS && e.code==ABS_MT_TRACKING_ID)g.tid[g.current_slot]=e.value;
        if(e.type==EV_SYN && e.code==SYN_REPORT) {
            /* Interleaved synthetic frames can change the sticky uinput slot. */
            if(!emit(EV_ABS,ABS_MT_SLOT,g.frame_open_slot)){stop_merge();return;}
            for(size_t j=0;j<g.frame_n;j++){
                struct input_event *p=&g.frame[j];
                if(!emit(p->type,p->code,p->value)){stop_merge();return;}
            }
            tool_buttons();sync_frame();g.frame_n=0;g.frame_open_slot=g.current_slot;
        } else if(!(e.type==EV_KEY && (e.code==BTN_TOUCH||
            (e.code>=BTN_TOOL_FINGER&&e.code<=BTN_TOOL_QUINTTAP)))) {
            if(g.frame_n==MAX_EVENTS){LOGE("touch frame overflow: fail-open");stop_merge();return;}
            g.frame[g.frame_n++]=e;
        }
    }
}
static void handle_sar(int fd) {
    struct input_event events[32];ssize_t n=read(fd,events,sizeof(events));
    if(n<=0||n%(ssize_t)sizeof(struct input_event)){stop_merge();return;}
    for(size_t k=0;k<(size_t)n/sizeof(events[0]);k++){
        struct input_event e=events[k];
        if(e.type==EV_SYN && e.code==SYN_DROPPED){stop_merge();return;}
        int which=(fd==g.left.fd)?0:1;
        if(e.type==EV_KEY && ((which==0&&e.code==KEY_F7)||(which==1&&e.code==KEY_F8)))
            g.sar_key[which]=e.value!=0;
        else if(e.type==EV_ABS && e.code==ABS_DISTANCE)
            g.sar_state[which]=e.value!=0;
        else continue;
        bool pressed=g.sar_key[which]||g.sar_state[which];
        if(pressed&&!g.virt_down[which])down_virtual(which);
        else if(!pressed&&g.virt_down[which])release_virtual(which);
    }
}
static void handle_pending(void) {
    struct input_event events[64];
    ssize_t n=read(g.touch.fd,events,sizeof(events));
    if(n<=0){stop_merge();return;}
    if(!touch_is_down()) {
        /* Close passive monitor and rediscover the input stream before GRAB. */
        close_device(&g.touch);close_device(&g.left);close_device(&g.right);
        g.pending=false;
        start_merge();
    }
}
int main(void) {
    mode_off();
    g.ep=epoll_create1(EPOLL_CLOEXEC);
    g.notify_fd=inotify_init1(IN_CLOEXEC|IN_NONBLOCK);
    if(g.ep<0||g.notify_fd<0){LOGE("epoll/inotify init failure");return 1;}
    g.notify_wd=inotify_add_watch(g.notify_fd,DIR_PATH,IN_MOVED_TO|IN_CLOSE_WRITE|IN_CREATE);
    if(g.notify_wd<0){LOGE("config watch failed; init dir missing?");return 1;}
    if(watch_add(g.notify_fd)<0)return 1;
    refresh();
    for(;;){
        struct epoll_event ev[8];
        int n=epoll_wait(g.ep,ev,8,-1);
        if(n<0){if(errno==EINTR)continue;break;}
        for(int i=0;i<n;i++) {
            int fd=ev[i].data.fd;
            if(fd==g.notify_fd) {
                char buffer[4096];while(read(fd,buffer,sizeof(buffer))>0){}
                refresh();
            } else if(g.pending && fd==g.touch.fd)handle_pending();
            else if(g.active && fd==g.touch.fd)handle_touch();
            else if(g.active && (fd==g.left.fd||fd==g.right.fd))handle_sar(fd);
        }
    }
    stop_merge();return 1;
}
