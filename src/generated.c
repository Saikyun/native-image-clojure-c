#include "generated.h"

int GET_SDL_INIT_VIDEO() { return SDL_INIT_VIDEO; }
int GET_SDL_WINDOW_SHOWN() { return SDL_WINDOW_SHOWN; }
void* get_null() { return NULL; }
char *gen_title() { return "SDL Tutorial"; }

SDL_Rect *create_rect(int x, int y, int w, int h) {
  SDL_Rect *r = (SDL_Rect*)malloc(sizeof(SDL_Rect));
  r->x = x;
  r->y = y;
  r->w = w;
  r->h = h;
  return r;
}

SDL_Event e;

SDL_Event *get_e() {
  return &e;
}
int  _SHADOWING_SDL_Init( Uint32  flags) {
  return SDL_Init(flags);
}
int  _SHADOWING_SDL_PollEvent( SDL_Event * event) {
  return SDL_PollEvent(event);
}
void  _SHADOWING_SDL_Delay( Uint32  ms) {
  SDL_Delay(ms);
}
int  _SHADOWING_SDL_UpdateWindowSurface( SDL_Window * window) {
  return SDL_UpdateWindowSurface(window);
}
SDL_Surface * _SHADOWING_SDL_GetWindowSurface( SDL_Window * window) {
  return SDL_GetWindowSurface(window);
}
Uint32  _SHADOWING_SDL_MapRGB(const SDL_PixelFormat * format,  Uint8  r,  Uint8  g,  Uint8  b) {
  return SDL_MapRGB(format, r, g, b);
}
SDL_Window * _SHADOWING_SDL_CreateWindow(const char * title,  int  x,  int  y,  int  w,  int  h,  Uint32  flags) {
  return SDL_CreateWindow(title, x, y, w, h, flags);
}
int  _SHADOWING_SDL_FillRect( SDL_Surface * dst, const SDL_Rect * rect,  Uint32  color) {
  return SDL_FillRect(dst, rect, color);
}
void  _SHADOWING_SDL_Quit() {
  SDL_Quit();
}